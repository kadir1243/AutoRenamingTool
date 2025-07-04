/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.art.api;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;

import net.neoforged.art.internal.*;
import net.neoforged.srgutils.IMappingFile;
import org.objectweb.asm.Type;

import static java.util.Objects.requireNonNull;

/**
 * A {@code Transformer} is the basic building block for transforming entries read from a JAR file.
 * Transformers can be registered to a {@link Renamer.Builder} to run over all entries.
 */
public interface Transformer {
    /**
     * Processes a class entry and returns the transformed entry.
     *
     * @param entry the original entry
     * @return the transformed entry
     */
    default ClassEntry process(ClassEntry entry) {
        return entry;
    }

    /**
     * Processes a manifest entry and returns the transformed entry.
     *
     * @param entry the original entry
     * @return the transformed entry
     */
    default ManifestEntry process(ManifestEntry entry) {
        return entry;
    }

    /**
     * Processes a resource entry and returns the transformed entry.
     *
     * @param entry the original entry
     * @return the transformed entry
     */
    default ResourceEntry process(ResourceEntry entry) {
        return entry;
    }

    /**
     * Processes a javadoctor entry and returns the transformed entry.
     *
     * @param entry the original entry
     * @return the transformed entry
     */
    default JavadoctorEntry process(JavadoctorEntry entry) {
        return entry;
    }

    /**
     * Returns extra entries to add to the JAR file.
     */
    default Collection<? extends Entry> getExtras() {
        return Collections.emptyList();
    }

    /**
     * Create a transformer that applies mappings as a transformation.
     *
     * @param map the mapping information to remap with
     * @param collectAbstractParams whether to collect abstract parameter names for FernFlower
     * @return a factory for a renaming transformer
     */
    static Factory renamerFactory(IMappingFile map, boolean collectAbstractParams) {
        return ctx -> new RenamingTransformer(ctx.getClassProvider(), map, ctx.getLog(), collectAbstractParams);
    }

    /**
     * Create a transformer that renames any local variables that are not valid java identifiers.
     *
     * @param config option for which local variables to rename
     * @return an identifier-fixing transformer
     */
    public static Factory identifierFixerFactory(final IdentifierFixerConfig config) {
        return ctx -> new IdentifierFixer(config);
    }

    /**
     * Create a transformer that fixes misaligned parameter annotations caused by Proguard.
     *
     * @return a factory for a parameter annotation-fixing transformer
     */
    public static Factory parameterAnnotationFixerFactory() {
        return ctx -> ParameterAnnotationFixer.INSTANCE;
    }

    /**
     * Create a transformer that removes the final attribute from parameter metadata.
     */
    public static Factory parameterFinalFlagRemoverFactory() {
        return ctx -> ParameterFinalFlagRemover.INSTANCE;
    }

    public static Factory innerClassFixerFactory(File exceptor) {
        return ctx -> new InnerClassFixer(ctx.getDebug(), exceptor);
    }

    public static Factory signatureInjectorFactory(File signaturizer) {
        return ctx -> new SignatureInjector(ctx.getDebug(), signaturizer);
    }

    /**
     * Create a transformer that applies line number corrections from Fernflower.
     *
     * @param sourceJar the source jar
     * @return a factory for a transformer that applies line number information
     */
    public static Factory fernFlowerLineFixerFactory(File sourceJar) {
        return ctx -> new FFLineFixer(ctx.getDebug(), sourceJar);
    }

    /**
     * Create a transformer that restores record component data stripped by ProGuard.
     *
     * @return a factory for a transformer that fixes record class metadata
     */
    public static Factory recordFixerFactory() {
        return ctx -> RecordFixer.INSTANCE;
    }

    /**
     * Create a transformer that fixes the {@code SourceFile} attribute of classes.
     * <p>
     * This attempts to infer a file name based on the supplied language information.
     *
     * @param config the method to use to generate a source file name.
     * @return a transformer that fixes {@code SourceFile} information
     */
    public static Factory sourceFixerFactory(SourceFixerConfig config) {
        return ctx -> new SourceFixer(config);
    }

    /**
     * Create a transformer that strips invalid code signing signatures from a manifest.
     *
     * @param config the variants of signatures to strip
     * @return a factory for a transformer that strips signatures
     */
    public static Factory signatureStripperFactory(SignatureStripperConfig config) {
        return ctx -> new SignatureStripperTransformer(ctx.getLog(), config);
    }

    default int getPriority() {
        return 0;
    }

    /**
     * A {@code Entry} is a single entry representing an entry in a JAR file.
     */
    public interface Entry {
        static final long STABLE_TIMESTAMP = 0x386D4380; //01/01/2000 00:00:00 java 8 breaks when using 0.

        /**
         * Returns the last modification time of this entry.
         *
         * @see ZipEntry#getTime()
         */
        long getTime();

        /**
         * Returns the full name of this entry, including folders and file extension,
         * relative to the root of the JAR file.
         *
         * @see ZipEntry#getName()
         */
        String getName();

        /**
         * Returns the bytes associated with this entry.
         */
        byte[] getData();

        /**
         * Runs the provided transformer over this entry and returns the transformed entry.
         *
         * @param transformer the transformer to run
         * @return the transformed entry
         */
        Entry process(Transformer transformer);
    }

    /**
     * A {@code ClassEntry} represents a class file entry in a JAR file.
     */
    public interface ClassEntry extends Entry {
        /**
         * Creates a default class entry.
         *
         * @param name the name of the entry
         * @param time the last modification time
         * @param data the raw class bytes
         * @return the class entry
         */
        static ClassEntry create(String name, long time, byte[] data) {
            return new EntryImpl.ClassEntry(name, time, data);
        }

        /**
         * Creates a default class entry for a multi-release class.
         *
         * @param cls the name of the class
         * @param time the last modification time
         * @param data the raw class bytes
         * @param version the java version
         * @return the class entry
         */
        static ClassEntry create(String cls, long time, byte[] data, int version) {
            return create("META-INF/versions/" + version + '/' +  cls + ".class", time, data);
        }

        /**
         * Returns the internal name of the class associated with this entry.
         *
         * @see Type#getInternalName()
         */
        String getClassName();

        /**
         * Returns {@code true} if this entry is a multi-release class.
         *
         * @see #getVersion()
         */
        boolean isMultiRelease();

        /**
         * Returns the java version associated with this multi-release class.
         * If this is not a multi-release class entry, the behavior is undefined.
         *
         * @return the java version associated with this multi-release class
         * @see #isMultiRelease()
         */
        int getVersion();
    }

    /**
     * A {@code ResourceEntry} represents a generic resource entry in a JAR file
     * that is not a class file, manifest or {@code javadoctor.json} file.
     */
    public interface ResourceEntry extends Entry {
        /**
         * Creates a default resource entry.
         *
         * @param name the name of the entry
         * @param time the last modification time
         * @param data the raw resource bytes
         * @return the resource entry
         */
        static ResourceEntry create(String name, long time, byte[] data) {
            return new EntryImpl.ResourceEntry(name, time, data);
        }
    }

    /**
     * A {@code ManifestEntry} represents a manifest entry in a JAR file.
     */
    public interface ManifestEntry extends Entry {
        /**
         * Creates a default manifest entry.
         * The name of this entry is always {@code META-INF/MANIFEST.MF}.
         *
         * @param time the last modification time
         * @param data the raw manifest bytes
         * @return the manifest entry
         */
        static ManifestEntry create(long time, byte[] data) {
            return new EntryImpl.ManifestEntry(time, data);
        }
    }

    /**
     * A {@code JavadoctorEntry} represents a {@code javadoctor.json} entry in a JAR file.
     */
    public interface JavadoctorEntry extends Entry {
        /**
         * Creates a default manifest entry.
         * The name of this entry is always {@code META-INF/MANIFEST.MF}.
         *
         * @param time the last modification time
         * @param data the raw manifest bytes
         * @return the manifest entry
         */
        static JavadoctorEntry create(long time, byte[] data) {
            return new EntryImpl.JavadoctorEntry(time, data);
        }
    }

    /**
     * A factory to create transformers using {@link Renamer} instance-specific information.
     */
    public interface Factory {
        /**
         * Create a new factory that always returns the same transformer instance.
         *
         * @param transformer the transformer
         * @return a new transformer factory
         */
        public static Factory always(final Transformer transformer) {
            requireNonNull(transformer, "transformer");
            return ctx -> transformer;
        }

        /**
         * Create a new transformer.
         *
         * @param ctx context
         * @return a transformer instance
         */
        Transformer create(Context ctx);
    }

    /**
     * Context providing renamer state when creating a transformer.
     */
    public interface Context {
        /**
         * Get a consumer that will handle standard-level logging output.
         *
         * @return the logging handler
         */
        Consumer<String> getLog();

        /**
         * Get a consumer that will handle debug-level logging output.
         *
         * @return the debug logging handler
         */
        Consumer<String> getDebug();

        /**
         * Get a class provider instance that holds centralized information
         * about class files from the registered class providers.
         *
         * @return the class provider instance
         */
        ClassProvider getClassProvider();
    }
}
