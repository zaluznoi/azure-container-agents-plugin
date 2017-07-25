/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.azure.containeragents.strategy;


import hudson.Extension;
import hudson.model.*;
import hudson.slaves.*;
import hudson.util.TimeUnit2;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class KubernetesOnceRetentionStrategy extends CloudRetentionStrategy implements ExecutorListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(KubernetesOnceRetentionStrategy.class);
    private static transient int idleMinutes = 1;
    private transient boolean terminating;

    @DataBoundConstructor
    public KubernetesOnceRetentionStrategy() {
        super(idleMinutes);
    }

    public int getIdleMinutes() {
        return idleMinutes;
    }

    @Override
    public long check(final AbstractCloudComputer c) {
        // When the slave is idle we should disable accepting tasks and check to see if it is already trying to
        // terminate. If it's not already trying to terminate then lets terminate manually.
        if (c.isIdle() && !disabled) {
            final long idleMilliseconds = System.currentTimeMillis() - c.getIdleStartMilliseconds();
            if (idleMilliseconds > TimeUnit2.MINUTES.toMillis(idleMinutes)) {
                LOGGER.info("Disconnecting {}", c.getName());
                done(c);
            }
        }

        // Return one because we want to check every minute if idle.
        return 1;
    }

    @Override
    public void start(AbstractCloudComputer c) {
        if (c.getNode() instanceof EphemeralNode) {
            throw new IllegalStateException("May not use OnceRetentionStrategy on an EphemeralNode: " + c);
        }
        super.start(c);
    }

    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
    }

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        done(executor);
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        done(executor);
    }

    private void done(Executor executor) {
        try {
            Thread.sleep(10 * 1000);
        } catch (Exception e) {
            LOGGER.info(e.getMessage());
        }
        final AbstractCloudComputer<?> c = (AbstractCloudComputer) executor.getOwner();
        Queue.Executable exec = executor.getCurrentExecutable();

        LOGGER.info("terminating {} since {} seems to be finished", c.getName(), exec);
        done(c);
    }

    private void done(final AbstractCloudComputer<?> c) {
        c.setAcceptingTasks(false); // just in case
        synchronized (this) {
            Computer.threadPoolForRemoting.submit(new Runnable() {
                @Override
                public void run() {
                    Queue.withLock( new Runnable() {
                        @Override
                        public void run() {
                            try {
                                AbstractCloudSlave node = c.getNode();
                                if (node != null) {
                                    node.terminate();
                                }
                            } catch (InterruptedException | IOException e) {
                                LOGGER.warn("Failed to terminate {}: {}", c.getName(), e);
                            }
                        }
                    });
                }
            });
        }

    }

    @Override
    public DescriptorImpl getDescriptor() {
        return DESCRIPTOR;
    }

    @Restricted(NoExternalUse.class)
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    @Extension
    public static final class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        @Override
        public String getDisplayName() {
            return "Kubernetes Once Retention Strategy";
        }
    }

}
