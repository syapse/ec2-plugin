/*
 * The MIT License
 *
 * Copyright (c) 2004-, Kohsuke Kawaguchi, Sun Microsystems, Inc., and a number of other of contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.ec2;

import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang.StringUtils;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.StartInstancesRequest;
import com.amazonaws.services.ec2.model.StartInstancesResult;
import com.google.common.base.Preconditions;

/**
 * {@link ComputerLauncher} for EC2 that waits for the instance to really come up before proceeding to the real
 * user-specified {@link ComputerLauncher}.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class EC2ComputerLauncher extends ComputerLauncher {
    @Override
    public void launch(SlaveComputer _computer, TaskListener listener) {
        try {
            EC2Computer computer = (EC2Computer) _computer;
            final String baseBaseMsg = "Node " + computer.getName();
            String msg = null;

            OUTER: while (true) {
                if (StringUtils.isBlank(computer.getInstanceId())) {
                    Preconditions.checkArgument(computer.getNode() instanceof EC2SpotSlave);
                    final String baseMsg = baseBaseMsg + " (SpotRequest " + computer.getSpotInstanceRequestId() + ")";
                    EC2SpotSlave ec2Slave = (EC2SpotSlave) computer.getNode();
                    if (ec2Slave.isSpotRequestDead()) {
                        // Terminate launch
                        return;
                    }

                    computer.updateInstanceIdFromSpotRequest();
                    msg = baseMsg + " SpotRequest is still requesting the instance, waiting 10s";
                }

                if (StringUtils.isNotBlank(computer.getInstanceId())) {
                    final String baseMsg = baseBaseMsg + " (" + computer.getInstanceId() + ")";
                    switch (computer.getState()) {
                        case PENDING:
                            msg = baseMsg + " is still pending/launching, waiting 5s";
                            break;
                        case STOPPING:
                            msg = baseMsg + " is still stopping, waiting 5s";
                            break;
                        case RUNNING:
                            msg = baseMsg + " is ready";
                            ((EC2Computer) _computer).getCloud().log(LOGGER, Level.FINER, listener, msg);
                            break OUTER;
                        case STOPPED:
                            msg = baseMsg + " is stopped, sending start request";
                            ((EC2Computer) _computer).getCloud().log(LOGGER, Level.FINER, listener, msg);

                            AmazonEC2 ec2 = computer.getCloud().connect();
                            List<String> instances = new ArrayList<String>();
                            instances.add(computer.getInstanceId());

                            StartInstancesRequest siRequest = new StartInstancesRequest(instances);
                            StartInstancesResult siResult = ec2.startInstances(siRequest);

                            msg = baseMsg + ": sent start request, result: " + siResult;
                            ((EC2Computer) _computer).getCloud().log(LOGGER, Level.FINER, listener, msg);
                            continue OUTER;
                        case SHUTTING_DOWN:
                        case TERMINATED:
                            // abort
                            msg = baseMsg + " is terminated or terminating, aborting launch";
                            ((EC2Computer) _computer).getCloud().log(LOGGER, Level.FINER, listener, msg);
                            return;
                        default:
                            msg = baseMsg + " is in an unknown state, retrying in 5s";
                            break;
                    }
                }

                // check every 10 seconds if in spot request phase, 5 seconds if instance already created
                long timeToWait = StringUtils.isBlank(computer.getInstanceId()) ? 10000L : 5000L;
                Thread.sleep(timeToWait);
                // and report to system log and console
                ((EC2Computer) _computer).getCloud().log(LOGGER, Level.FINEST, listener, msg);
            }

            launch(computer, listener, computer.describeInstance());
        } catch (AmazonClientException e) {
            e.printStackTrace(listener.error(e.getMessage()));
        } catch (IOException e) {
            e.printStackTrace(listener.error(e.getMessage()));
        } catch (InterruptedException e) {
            e.printStackTrace(listener.error(e.getMessage()));
        }

    }

    /**
     * Stage 2 of the launch. Called after the EC2 instance comes up.
     */
    protected abstract void launch(EC2Computer computer, TaskListener listener, Instance inst)
            throws AmazonClientException, IOException, InterruptedException;

    private static final Logger LOGGER = Logger.getLogger(EC2ComputerLauncher.class.getName());
}
