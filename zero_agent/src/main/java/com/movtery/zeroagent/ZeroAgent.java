package com.movtery.zeroagent;

import net.kdt.pojavlaunch.IpcServer;

import java.lang.instrument.Instrumentation;

/**
 * ZeroAgent – Java agent entry point.
 *
 * Loaded by the JVM via -javaagent:zero_agent.jar before Minecraft starts.
 * Registers AgentCommandHandler with the already-running IpcServer so the
 * launcher can query class information from the game JVM.
 *
 * IpcServer is started by MainActivity.onCreate() (in the :game process) before
 * the game JVM is launched, so it is always available when premain runs.
 */
public class ZeroAgent {

    public static void premain(String args, Instrumentation inst) {
        init(inst);
    }

    /** Called if the agent is attached after JVM startup (e.g. via attach API). */
    public static void agentmain(String args, Instrumentation inst) {
        init(inst);
    }

    private static void init(Instrumentation inst) {
        try {
            IpcServer.get().setCommandHandler(new AgentCommandHandler(inst));
            AllocationTracker.setEventListener(new AllocationTracker.IpcEventListener() {
                @Override
                public void onAllocationEvent(String className, StackTraceElement[] stackTrace) {
                    try {
                        byte[] payload = AllocationTracker.encodeEventPayload(className, stackTrace);
                        IpcServer.get().sendUnsolicited(IpcServer.CMD_PUSH_EVENT, payload);
                    } catch (Throwable ignored) {
                    }
                }
            });
            inst.addTransformer(new AllocationTransformer(), true);
            System.out.println("[ZeroAgent] CommandHandler and allocation transformer registered");
        } catch (Throwable t) {
            System.err.println("[ZeroAgent] Failed to initialize agent: " + t);
        }
    }
}
