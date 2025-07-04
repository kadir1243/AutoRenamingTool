/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.art;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import net.neoforged.art.api.IdentifierFixerConfig;
import net.neoforged.art.api.Renamer;
import net.neoforged.art.api.SignatureStripperConfig;
import net.neoforged.art.api.SourceFixerConfig;
import net.neoforged.art.api.Transformer;
import net.neoforged.srgutils.IMappingFile;

public class Main {
    public static void main(String[] args) throws IOException {
        OptionParser parser = new OptionParser();
        OptionSpec<File> inputO  = parser.accepts("input",  "Input jar file").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> outputO = parser.accepts("output", "Output jar file, if unspecifed, overwrites input").withRequiredArg().ofType(File.class);
        OptionSpec<File> mapO    = parser.acceptsAll(Arrays.asList("map", "names"),    "Mapping file to apply").withRequiredArg().ofType(File.class);
        OptionSpec<File> logO    = parser.accepts("log",    "File to log data to, optional, defaults to System.out").withRequiredArg().ofType(File.class);
        OptionSpec<File> libO    = parser.acceptsAll(Arrays.asList("lib", "e"), "Additional library to use for inheritance").withRequiredArg().ofType(File.class);
        OptionSpec<Void> fixAnnO = parser.accepts("ann-fix", "Fixes misaligned parameter annotations caused by Proguard");
        OptionSpec<Void> unfinalParams0 = parser.accepts("unfinal-params", "Remove final flag from parameters");
        OptionSpec<File> innerClassFix0 = parser.accepts("inner-class-fix", "Uses exceptor.json to add inner class attributes").withOptionalArg().ofType(File.class);
        OptionSpec<File> injectSignatureO = parser.accepts("inject-signatures", "Uses signaturizer.json to add signature attributes").withOptionalArg().ofType(File.class);
        OptionSpec<Void> fixRecordsO = parser.accepts("record-fix", "Fixes record components and attributes stripped by Proguard.");
        OptionSpec<IdentifierFixerConfig> fixIdsO = parser.accepts("ids-fix", "Fixes local variables that are not valid java identifiers.").withOptionalArg().withValuesConvertedBy(new EnumConverter<>(IdentifierFixerConfig.class)).defaultsTo(IdentifierFixerConfig.ALL);
        OptionSpec<SourceFixerConfig> fixSrcO = parser.accepts("src-fix", "Fixes the 'SourceFile' attribute of classes.").withOptionalArg().withValuesConvertedBy(new EnumConverter<>(SourceFixerConfig.class)).defaultsTo(SourceFixerConfig.JAVA);
        OptionSpec<SignatureStripperConfig> stripSigsO = parser.accepts("strip-sigs", "Strip invalid codesigning signatures from the Jar manifest").withOptionalArg().withValuesConvertedBy(new EnumConverter<>(SignatureStripperConfig.class)).defaultsTo(SignatureStripperConfig.ALL);
        OptionSpec<Integer> threadsO = parser.accepts("threads", "Number of threads to use, defaults to processor count.").withRequiredArg().ofType(Integer.class).defaultsTo(Runtime.getRuntime().availableProcessors());
        OptionSpec<File> ffLinesO = parser.accepts("ff-line-numbers", "Applies line number corrections from Fernflower.").withRequiredArg().ofType(File.class);
        OptionSpec<Void> reverseO = parser.accepts("reverse", "Reverse provided mapping file before applying");
        OptionSpec<Void> disableAbstractParam = parser.accepts("disable-abstract-param", "Disables collection of names of parameters of abstract methods for FernFlower");
        OptionSet options;
        try {
            options = parser.parse(expandArgs(args));
        } catch (OptionException ex) {
            System.err.println("Error: " + ex.getMessage());
            System.err.println();
            parser.printHelpOn(System.err);
            System.exit(1);
            return;
        }

        Consumer<String> log = ln -> {
            if (!ln.isEmpty()) {
                System.out.println(ln);
            }
        };
        if (options.has(logO)) {
            PrintStream out = System.out;
            PrintStream file = new PrintStream(new FileOutputStream(options.valueOf(logO)));
            log = ln -> {
                if (!ln.isEmpty()) {
                    out.println(ln);
                    file.println(ln);
                }
            };
        }

        log.accept("Forge Auto Renaming Tool v" + getVersion());
        Renamer.Builder builder = Renamer.builder();
        builder.withJvmClasspath();
        builder.logger(log);

        // Move this up top so that the log lines are above the rest of the config as they can be spammy.
        // Its useful information but we care more about the specific configs.
        if (options.has(libO)) {
            for (File lib : options.valuesOf(libO)) {
                log.accept("lib: " + lib.getAbsolutePath());
                builder.lib(lib);
            }
        }

        log.accept("log: " + (options.has(logO) ? options.valueOf(logO).getAbsolutePath() : "null"));

        File inputF = options.valueOf(inputO);
        log.accept("input: " + inputF.getAbsolutePath());

        File outputF = options.has(outputO) ? options.valueOf(outputO) : inputF;
        log.accept("output: " + outputF.getAbsolutePath());

        log.accept("threads: " + options.valueOf(threadsO));
        builder.threads(options.valueOf(threadsO));

        // Map is optional so that we can run other fixes without renaming.
        // This does mean that it's not strictly a 'renaming' tool but screw it I like the name.
        if (options.has(mapO)) {
            List<File> mapF = options.valuesOf(mapO);
            log.accept("Names: " + mapF.stream().map(File::getAbsolutePath).collect(Collectors.joining(", ")) + "(reversed: " + options.has(reverseO) + ")");
            IMappingFile mappings = IMappingFile.load(mapF.get(0));
            for (int i = 1; i < mapF.size(); i++) {
                mappings = mappings.merge(IMappingFile.load(mapF.get(i)));
            }
            if (options.has(reverseO)) {
                mappings = mappings.reverse();
            }

            builder.add(Transformer.renamerFactory(mappings, !options.has(disableAbstractParam)));
        } else {
            log.accept("Names: null");
        }

        if (options.has(fixAnnO)) {
            log.accept("Fix Annotations: true");
            builder.add(Transformer.parameterAnnotationFixerFactory());
        } else {
            log.accept("Fix Annotations: false");
        }

        if (options.has(unfinalParams0)) {
            log.accept("Unfinal Parameters: true");
            builder.add(Transformer.parameterFinalFlagRemoverFactory());
        } else {
            log.accept("Unfinal Parameters: false");
        }

        if (options.has(innerClassFix0)) {
            log.accept("Inner Class Fixer: true");
            builder.add(Transformer.innerClassFixerFactory(options.valueOf(innerClassFix0)));
        } else {
            log.accept("Inner Class Fixer: false");
        }

        if (options.has(injectSignatureO)) {
            log.accept("Signature Injector: true");
            builder.add(Transformer.signatureInjectorFactory(options.valueOf(injectSignatureO)));
        } else {
            log.accept("Signature Injector: false");
        }

        if (options.has(fixRecordsO)) {
            log.accept("Fix Records: true");
            builder.add(Transformer.recordFixerFactory());
        } else {
            log.accept("Fix Records: false");
        }

        if (options.has(fixIdsO)) {
            log.accept("Fix Identifiers: " + options.valueOf(fixIdsO));
            builder.add(Transformer.identifierFixerFactory(options.valueOf(fixIdsO)));
        } else {
            log.accept("Fix Identifiers: false");
        }

        if (options.has(fixSrcO)) {
            log.accept("Fix SourceFile: " + options.valueOf(fixSrcO));
            builder.add(Transformer.sourceFixerFactory(options.valueOf(fixSrcO)));
        } else {
            log.accept("Fix SourceFile: false");
        }

        if (options.has(ffLinesO)) {
            File lines = options.valueOf(ffLinesO);
            log.accept("Fix Line Numbers: " + lines.getAbsolutePath());
            builder.add(Transformer.fernFlowerLineFixerFactory(lines));
        } else {
            log.accept("Fix Line Numbers: false");
        }

        if (options.has(stripSigsO)) {
            SignatureStripperConfig config = options.valueOf(stripSigsO);
            log.accept("Strip codesigning signatures: " + config);
            builder.add(Transformer.signatureStripperFactory(config));
        } else {
            log.accept("Strip codesigning signatures: false");
        }

        try (Renamer renamer = builder.build()) {
            renamer.run(inputF, outputF);
        }
    }

    private static String[] expandArgs(String[] args) throws IOException {
        List<String> ret = new ArrayList<>();
        for (int x = 0; x < args.length; x++) {
            if (args[x].equals("--cfg")) {
                if (x + 1 == args.length)
                    throw new IllegalArgumentException("No value specified for '--cfg'");

                try (Stream<String> lines = Files.lines(Paths.get(args[++x]))) {
                    lines.forEach(ret::add);
                }
            } else if (args[x].startsWith("--cfg=")) {
                try (Stream<String> lines = Files.lines(Paths.get(args[x].substring(6)))) {
                    lines.forEach(ret::add);
                }
            } else {
                ret.add(args[x]);
            }
        }

        return ret.toArray(new String[ret.size()]);
    }

    private static String getVersion() {
        final String ver = Main.class.getPackage().getImplementationVersion();
        return ver == null ? "UNKNOWN" : ver;
    }

    private static class EnumConverter<T extends Enum<T>> extends joptsimple.util.EnumConverter<T> {
        private EnumConverter(Class<T> enumClazz) {
            super(enumClazz);
        }
    }
}
