/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.art.internal;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.art.api.Transformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.BufferedReader;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * File Format:
 * {@code
 *     [
 *         {
 *             "className": "ABC"
 *             "methods": [
 *                 {
 *                     "name": "methodabc"
 *                     "descriptor": "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List", // optional
 *                     "signature": "Ljava/util/List&lt;[Ljava/util/List&lt;Ljava/lang/String;&gt;;&gt;;" // List&lt;List&lt;String&gt;[]&gt;
 *                 }
 *             ],
 *             "fields": [
 *                 {
 *                     "name": "fieldabc",
 *                     "descriptor": "Ljava/lang/Object;" // optional
 *                     "signature": "Signature of field"
 *                 }
 *             ],
 *             "signature": "signature of class"
 *         }
 *     ]
 * }
 */
public final class SignatureInjector implements Transformer {
    private static final Gson GSON = new Gson();
    private final Map<String, Signaturizer> classes = new HashMap<>();

    public SignatureInjector(Consumer<String> debug, File data) {
        try (BufferedReader fis = Files.newBufferedReader(data.toPath())) {
            JsonElement jsonElement = JsonParser.parseReader(fis);
            if (jsonElement.isJsonArray()) {
                for (JsonElement entry : jsonElement.getAsJsonArray()) {
                    Signaturizer signaturizer = GSON.fromJson(entry, Signaturizer.class);
                    classes.put(signaturizer.className, signaturizer);
                }
            } else if (jsonElement.isJsonObject()) {
                Signaturizer signaturizer = GSON.fromJson(jsonElement, Signaturizer.class);
                classes.put(signaturizer.className, signaturizer);
            } else {
                throw new UnsupportedOperationException("Can not parse json");
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not create SignatureInjector for file: " + data.getAbsolutePath(), e);
        }
        debug.accept("Found Generic Info Injector Data: " + classes);
    }

    private static class Signaturizer {
        public String className;
        public String signature;
        public List<MethodSignature> methods = new ArrayList<>();
        public List<FieldSignature> fields = new ArrayList<>();

        private static class MethodSignature {
            public String name;
            public String descriptor;
            public String signature;
        }

        private static class FieldSignature {
            public String name;
            public String descriptor;
            public String signature;
        }
    }

    @Override
    public ClassEntry process(ClassEntry entry) {
        if (!classes.containsKey(entry.getClassName())) {
            return entry;
        }
        ClassReader reader = new ClassReader(entry.getData());
        ClassWriter writer = new ClassWriter(reader, 0);
        ClassNode classNode = new ClassNode();

        Signaturizer signaturizer = classes.get(entry.getClassName());
        reader.accept(new Injector(writer, signaturizer), 0);

        classNode.accept(writer);

        return ClassEntry.create(entry.getName(), entry.getTime(), writer.toByteArray());
    }

    private static class Injector extends ClassVisitor {
        private final Signaturizer data;
        private final Map<String, Signaturizer.FieldSignature> fields;
        private final Map<String, Signaturizer.MethodSignature> methods;
        public Injector(ClassVisitor classVisitor, Signaturizer data) {
            super(RenamerImpl.MAX_ASM_VERSION, classVisitor);
            this.data = data;
            fields = data.fields.stream().collect(Collectors.toMap(f -> f.name + ':' + f.descriptor, Function.identity()));
            methods = data.methods.stream().collect(Collectors.toMap(f -> f.name + ':' + f.descriptor, Function.identity()));
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            super.visit(version, access, name, data.signature == null ? signature : data.signature, superName, interfaces);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            Signaturizer.FieldSignature fieldSignature = fields.get(name + ':' + descriptor);
            if (fieldSignature == null) {
                fieldSignature = fields.get(name + ':' + null);
            }
            if (fieldSignature == null) return super.visitField(access, name, descriptor, signature, value);

            return super.visitField(access, name, descriptor, fieldSignature.signature == null ? signature : fieldSignature.signature, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            Signaturizer.MethodSignature methodSignature = methods.get(name + ':' + descriptor);
            if (methodSignature == null) {
                methodSignature = methods.get(name + ':' + null);
            }
            if (methodSignature == null) return super.visitMethod(access, name, descriptor, signature, exceptions);

            return super.visitMethod(access, name, descriptor, methodSignature.signature == null ? signature : methodSignature.signature, exceptions);
        }
    }
}
