package org.apache.lucene.search.positions;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import org.apache.lucene.search.positions.IntervalQueue.IntervalRef;
import org.apache.lucene.util.PriorityQueue;

/**
 * 
 * @lucene.experimental
 */
// nocommit - javadoc
abstract class IntervalQueue extends PriorityQueue<IntervalRef> {
  final Interval currentCandidate = new Interval(
      Integer.MIN_VALUE, Integer.MIN_VALUE, -1, -1);

  public void reset() {
    clear();
    currentCandidate.begin = Integer.MIN_VALUE;
    currentCandidate.end = Integer.MIN_VALUE;
    currentCandidate.offsetBegin = -1;
    currentCandidate.offsetEnd = -1;
  }

  abstract public void updateCurrentCandidate();

  public IntervalQueue(int size) {
    super(size);
  }

  final static class IntervalRef {
    Interval interval;
    int index;

    IntervalRef(Interval interval, int index) {
      super();
      this.interval = interval;
      this.index = index;
    }
  }

}