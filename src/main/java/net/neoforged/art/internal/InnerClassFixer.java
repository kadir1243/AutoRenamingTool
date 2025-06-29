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

import java.io.BufferedReader;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;

public final class InnerClassFixer implements Transformer {
    private static final Gson GSON = new Gson();
    private final Map<String, ExceptorData> classes = new HashMap<>();

    public InnerClassFixer(Consumer<String> debug, File data) {
        try (BufferedReader fis = Files.newBufferedReader(data.toPath())) {
            JsonObject jsonElement = JsonParser.parseReader(fis).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : jsonElement.asMap().entrySet()) {
                classes.put(entry.getKey(), GSON.fromJson(entry.getValue(), ExceptorData.class));
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not create InnerClassFixer for file: " + data.getAbsolutePath(), e);
        }
        debug.accept("Found Inner Class Data: " + classes);
    }

    @Override
    public ClassEntry process(ClassEntry entry) {
        if (!classes.containsKey(entry.getClassName())) {
            return entry;
        }
        ClassReader reader = new ClassReader(entry.getData());
        ClassWriter writer = new ClassWriter(reader, 0);

        Fixer fixer = new Fixer(writer, Objects.requireNonNull(classes.get(entry.getClassName()), "How????"));
        reader.accept(fixer, 0);

        return ClassEntry.create(entry.getName(), entry.getTime(), writer.toByteArray());
    }

    @Override
    public int getPriority() {
        return 999;
    }

    private static class ExceptorData {
        public EnclosingMethod enclosingMethod = null;
        public List<InnerClass> innerClasses = null;

        public static class EnclosingMethod {
            public final String owner;
            public final String name;
            public final String desc;

            EnclosingMethod(String owner, String name, String desc) {
                this.owner = owner;
                this.name = name;
                this.desc = desc;
            }
        }

        public static class InnerClass {
            public final String inner_class;
            public final String outer_class;
            public final String inner_name;
            public final String access;

            InnerClass(String inner_class, String outer_class, String inner_name, String access) {
                this.inner_class = inner_class;
                this.outer_class = outer_class;
                this.inner_name = inner_name;
                this.access = access;
            }

            public int getAccess() {
                int ret = Integer.parseInt(access == null ? "0" : access, 16);
                ret &= ~Opcodes.ACC_SUPER; //Hack fix for old data, ACC_SUPER is invalid in InnerClasses.
                return ret;
            }
        }
    }

    private static class Fixer extends ClassVisitor {
        private final ExceptorData exceptorData;

        public Fixer(ClassVisitor parent, ExceptorData exceptorData) {
            super(RenamerImpl.MAX_ASM_VERSION, parent);
            this.exceptorData = exceptorData;
        }

        private final List<String> innerClass = new ArrayList<>();

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            super.visitInnerClass(name, outerName, innerName, access);
            innerClass.add(name);
        }

        private boolean outerClassInjectedAlready;

        @Override
        public void visitOuterClass(String owner, String name, String descriptor) {
            ExceptorData.EnclosingMethod enclosingMethod = this.exceptorData.enclosingMethod;
            if (enclosingMethod != null && !Objects.equals(enclosingMethod.owner, owner)) {
                super.visitOuterClass(enclosingMethod.owner, enclosingMethod.name, enclosingMethod.desc);
                outerClassInjectedAlready = true;
            } else {
                super.visitOuterClass(owner, name, descriptor);
            }
        }

        @Override
        public void visitEnd() {
            List<ExceptorData.InnerClass> innerClasses = this.exceptorData.innerClasses;
            if (innerClasses != null) {
                for (ExceptorData.InnerClass innerClassData : innerClasses) {
                    if (!this.innerClass.contains(innerClassData.inner_class)) {
                        super.visitInnerClass(innerClassData.inner_class, innerClassData.outer_class, innerClassData.inner_name, innerClassData.getAccess());
                    }
                }
            }
            if (!outerClassInjectedAlready) {
                ExceptorData.EnclosingMethod enclosingMethod = this.exceptorData.enclosingMethod;
                if (enclosingMethod != null) {
                    super.visitOuterClass(enclosingMethod.owner, enclosingMethod.name, enclosingMethod.desc);
                }
            }
            super.visitEnd();
        }
    }
}
