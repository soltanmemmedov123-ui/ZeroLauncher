package com.movtery.zeroagent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class AllocationTransformer implements ClassFileTransformer {

    private static final String TRACKER_INTERNAL = "com/movtery/zeroagent/AllocationTracker";
    private static final String[] SKIP_PREFIXES = {
            "java/", "javax/", "sun/", "jdk/", "com/sun/", "org/objectweb/asm/",
            "org/slf4j/", "org/junit/", "org/hamcrest/", "kotlin/", "android/", "dalvik/",
            "com/movtery/zeroagent/", "net/kdt/pojavlaunch/"
    };

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || shouldSkipClass(className)) {
            return null;
        }

        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"<init>".equals(name) || (access & Opcodes.ACC_NATIVE) != 0) {
                        return mv;
                    }
                    return new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
                        @Override
                        protected void onMethodEnter() {
                            visitLdcInsn(className.replace('/', '.'));
                            visitMethodInsn(INVOKESTATIC,
                                    "java/lang/Thread",
                                    "currentThread",
                                    "()Ljava/lang/Thread;",
                                    false);
                            visitMethodInsn(INVOKEVIRTUAL,
                                    "java/lang/Thread",
                                    "getStackTrace",
                                    "()[Ljava/lang/StackTraceElement;",
                                    false);
                            visitMethodInsn(INVOKESTATIC,
                                    TRACKER_INTERNAL,
                                    "trackAllocation",
                                    "(Ljava/lang/String;[Ljava/lang/StackTraceElement;)V",
                                    false);
                        }
                    };
                }
            };
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);
            return writer.toByteArray();
        } catch (Throwable t) {
            System.err.println("[AllocationTransformer] failed to transform " + className + ": " + t);
            return null;
        }
    }

    private static boolean shouldSkipClass(String className) {
        for (String prefix : SKIP_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
