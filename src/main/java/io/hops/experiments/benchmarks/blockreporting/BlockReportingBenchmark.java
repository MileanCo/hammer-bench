/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.hops.experiments.benchmarks.blockreporting;

import com.google.common.collect.Lists;
import io.hops.experiments.benchmarks.common.BenchMarkFileSystemName;
import io.hops.experiments.benchmarks.common.Benchmark;
import io.hops.experiments.benchmarks.common.config.BMConfiguration;
import io.hops.experiments.controller.Logger;
import io.hops.experiments.controller.commands.BenchmarkCommand;
import io.hops.experiments.controller.commands.WarmUpCommand;
import io.hops.experiments.utils.DFSOperationsUtils;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.util.Time;


import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class BlockReportingBenchmark extends Benchmark {

  private static final Random rand = new Random(UUID.randomUUID().getLeastSignificantBits());
  private long startTime;
  private AtomicInteger successfulOps = new AtomicInteger(0);
  private AtomicInteger failedOps = new AtomicInteger(0);
  private DescriptiveStatistics getNewNameNodeElapsedTime = new DescriptiveStatistics();
  private DescriptiveStatistics brElapsedTimes = new DescriptiveStatistics();
  private TinyDatanodes datanodes;
  private BMConfiguration bmConf;
  private final int slaveId ;

  public BlockReportingBenchmark(Configuration conf, BMConfiguration bmConf, int slaveID) {
    super(conf, bmConf);
    this.slaveId = slaveID;
  }

  @Override
  protected WarmUpCommand.Response warmUp(WarmUpCommand.Request warmUpReq)
          throws Exception {
    try{
    this.bmConf = ((BlockReportingWarmUp.Request) warmUpReq).getBMConf();
    datanodes = new TinyDatanodes(conf,  bmConf, slaveId);

    datanodes.leaveSafeMode();

    long t = Time.now();
    datanodes.generateInput();
    Logger.printMsg("WarmUp done in " + (Time.now() - t) / 1000 + " seconds");

    }catch(Exception e){
      Logger.error(e);
      throw e;
    }
    return new BlockReportingWarmUp.Response();
  }

  @Override
  protected BenchmarkCommand.Response processCommandInternal(BenchmarkCommand.Request command)
          throws Exception {

    List workers = Lists.newArrayList();
    for (int dn = 0; dn < bmConf.getSlaveNumThreads(); dn++) {
      workers.add(new Reporter(dn,
              bmConf.getBlockReportingMinTimeBeforeNextReport(),
              bmConf.getBlockReportingMaxTimeBeforeNextReport()));
    }

    startTime = Time.now();
    executor.invokeAll(workers, bmConf.getBlockReportBenchMarkDuration(), TimeUnit.MILLISECONDS);
    executor.shutdown();
    double speed = currentSpeed();
    datanodes.printStats();
    datanodes.stopProxies();

    return new BlockReportingBenchmarkCommand.Response(successfulOps.get(),
            failedOps.get(), speed, brElapsedTimes.getMean(),
            getNewNameNodeElapsedTime.getMean(),datanodes.getNNCount());
  }

  private class Reporter implements Callable {

    private final int dnIdx;
    private final int minTimeBeforeNextReport;
    private final int maxTimeBeforeNextReport;

    public Reporter(int dnIdx, int minTimeBeforeNextReport,
            int maxTimeBeforeNextReport) {
      this.dnIdx = dnIdx;
      this.minTimeBeforeNextReport = minTimeBeforeNextReport;
      this.maxTimeBeforeNextReport = maxTimeBeforeNextReport;
    }

    @Override
    public Object call() throws Exception {
      while (true) {
        try {

          if (minTimeBeforeNextReport > 0 && maxTimeBeforeNextReport > 0) {
            long sleep = minTimeBeforeNextReport + rand.nextInt(maxTimeBeforeNextReport - minTimeBeforeNextReport);
            Thread.sleep(sleep);
          }

          long[] ts = datanodes.executeOp(dnIdx);
          successfulOps.incrementAndGet();
          getNewNameNodeElapsedTime.addValue(ts[0]);
          brElapsedTimes.addValue(ts[1]);

          if (Logger.canILog()) {
            Logger.printMsg("Successful Rpts: " + successfulOps.get() + ", Failed Rpts: " + failedOps.get() + ", Speed: "
                    + DFSOperationsUtils.round(currentSpeed()) + " ops/sec,  Select NN for Rpt " + "[Avg,Min,Max]:  ["
                    + DFSOperationsUtils.round(getNewNameNodeElapsedTime.getMean())+", "
                    + DFSOperationsUtils.round(getNewNameNodeElapsedTime.getMin())+", "
                    + DFSOperationsUtils.round(getNewNameNodeElapsedTime.getMax())+"] "
                    +" Blk Rept [Avg,Min,Max]: ["
                    + DFSOperationsUtils.round(brElapsedTimes.getMean())+", "
                    + DFSOperationsUtils.round(brElapsedTimes.getMin())+", "
                    + DFSOperationsUtils.round(brElapsedTimes.getMax())+"]"
                    );
          }
        } catch (ClosedByInterruptException e) {
        } catch (InterruptedException e ){
        } catch (IOException e) {
        }  catch (Exception e) {
          failedOps.incrementAndGet();
          System.out.println(e);
          Logger.error(e);
        }
      }
    }
  }

  double currentSpeed() {
    double timePassed = Time.now() - startTime;
    double opsPerMSec = (double) (successfulOps.get()) / timePassed;
    return opsPerMSec * 1000;
  }
}
