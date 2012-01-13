/*
 * Copyright 2011 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.walkaround.wave.server.googleimport;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Multimap;
import com.google.inject.Inject;
import com.google.walkaround.proto.FindRemoteWavesTask;
import com.google.walkaround.proto.ImportWaveletTask;
import com.google.walkaround.proto.ImportWaveletTask.ImportSharingMode;
import com.google.walkaround.util.server.RetryHelper.PermanentFailure;
import com.google.walkaround.wave.server.gxp.SourceInstance;

import org.waveprotocol.wave.model.id.WaveId;
import org.waveprotocol.wave.model.id.WaveletId;
import org.waveprotocol.wave.model.id.WaveletName;
import org.waveprotocol.wave.model.util.Pair;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Knows about different task types and how to operate on them.
 *
 * @author ohler@google.com (Christian Ohler)
 */
public class TaskDispatcher {

  @SuppressWarnings("unused")
  private static final Logger log = Logger.getLogger(TaskDispatcher.class.getName());

  private final SourceInstance.Factory sourceInstanceFactory;
  private final FindRemoteWavesProcessor findWavesProcessor;
  private final ImportWaveletProcessor importWaveProcessor;

  @Inject
  public TaskDispatcher(SourceInstance.Factory sourceInstanceFactory,
      FindRemoteWavesProcessor findWavesProcessor,
      ImportWaveletProcessor importWaveProcessor) {
    this.sourceInstanceFactory = sourceInstanceFactory;
    this.findWavesProcessor = findWavesProcessor;
    this.importWaveProcessor = importWaveProcessor;
  }

  private String taskAgePrefix(ImportTask task) {
    long age = System.currentTimeMillis() - task.getCreationTimeMillis();
    long secs = age / 1000;
    long minutes = secs / 60;
    long hours = minutes / 60;
    long days = hours / 24;
    if (days > 1) {
      return "(over " + days + " days old) ";
    } else if (hours > 1) {
      return "(over " + hours + " hours old) ";
    } else if (minutes > 5) {
      return "(over " + minutes + " minutes old) ";
    } else {
      // Not worth mentioning.
      return "";
    }
  }

  public String describeTask(ImportTask task) {
    if (task.getPayload().hasFindWavesTask()) {
      FindRemoteWavesTask t = task.getPayload().getFindWavesTask();
      return taskAgePrefix(task)
          + "Find waves on "
          + sourceInstanceFactory.parseUnchecked(t.getInstance()).getShortName()
          + " between " + DaysSinceEpoch.toLocalDate(t.getOnOrAfterDays())
          + " and " + DaysSinceEpoch.toLocalDate(t.getBeforeDays());
    } else if (task.getPayload().hasImportWaveletTask()) {
      ImportWaveletTask t = task.getPayload().getImportWaveletTask();
      String sharingMode;
      switch (t.getSharingMode()) {
        case PRIVATE:
          sharingMode = "Private";
          break;
        case SHARED:
          sharingMode = "Shared";
          break;
        default:
          throw new AssertionError("Unexpected SharingMode: " + t.getSharingMode());
      }
      return taskAgePrefix(task)
          + sharingMode + " import of wavelet " + t.getWaveId() + " " + t.getWaveletId()
          + " from " + sourceInstanceFactory.parseUnchecked(t.getInstance()).getShortName();
    } else {
      throw new AssertionError("Unknown task payload type: " + task);
    }
  }

  public Multimap<Pair<SourceInstance, WaveletName>, ImportSharingMode> waveletImportsInProgress(
      List<ImportTask> tasksInProgress) {
    ImmutableSetMultimap.Builder<Pair<SourceInstance, WaveletName>, ImportSharingMode> out =
        ImmutableSetMultimap.builder();
    for (ImportTask task : tasksInProgress) {
      if (task.getPayload().hasFindWavesTask()) {
        // nothing
      } else if (task.getPayload().hasImportWaveletTask()) {
        ImportWaveletTask t = task.getPayload().getImportWaveletTask();
        out.put(
            Pair.of(sourceInstanceFactory.parseUnchecked(t.getInstance()),
                WaveletName.of(WaveId.deserialise(t.getWaveId()),
                    WaveletId.deserialise(t.getWaveletId()))),
            t.getSharingMode());
      } else {
        throw new AssertionError("Unknown task payload type: " + task);
      }
    }
    return out.build();
  }

  private boolean exactlyOneTrue(Boolean... args) {
    return Collections.frequency(Arrays.asList(args), true) == 1;
  }

  public void processTask(ImportTask task) throws IOException {
    Preconditions.checkArgument(exactlyOneTrue(
            task.getPayload().hasFindWavesTask(),
            task.getPayload().hasImportWaveletTask()),
        "Need exactly one type of payload: %s", task);
    try {
      if (task.getPayload().hasFindWavesTask()) {
        findWavesProcessor.findWaves(task.getPayload().getFindWavesTask());
      } else if (task.getPayload().hasImportWaveletTask()) {
        importWaveProcessor.importWavelet(task.getPayload().getImportWaveletTask());
      } else {
        throw new AssertionError("Unknown task payload type: " + task);
      }
    } catch (PermanentFailure e) {
      throw new IOException("Permanent failure processing task " + task, e);
    }
  }

}
