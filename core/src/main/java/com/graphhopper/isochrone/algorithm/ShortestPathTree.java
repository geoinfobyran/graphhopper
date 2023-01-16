/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.isochrone.algorithm;

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.cursors.ObjectCursor;
import com.graphhopper.coll.GHIntObjectHashMap;
import com.graphhopper.routing.AbstractRoutingAlgorithm;
import com.graphhopper.routing.Path;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.Graph;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.GHUtility;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.graphhopper.isochrone.algorithm.ShortestPathTree.ExploreType.*;
import static java.util.Comparator.comparingDouble;

/**
 * Computes a shortest path tree by a given weighting. Terminates when all shortest paths up to
 * a given travel time, distance, or weight have been explored.
 * <p>
 * IMPLEMENTATION NOTE:
 * util.PriorityQueue doesn't support efficient removes. We work around this by giving the labels
 * a deleted flag, not remove()ing them, and popping deleted elements off both queues.
 * Note to self/others: If you think this optimization is not needed, please test it with a scenario
 * where updates actually occur a lot, such as using finite, non-zero u-turn costs.
 *
 * @author Peter Karich
 * @author Michael Zilske
 */
public class ShortestPathTree extends AbstractRoutingAlgorithm {

    enum ExploreType {TIME, DISTANCE, WEIGHT}

    private static final Logger logger = LoggerFactory.getLogger(ShortestPathTree.class);

    public static class IsoLabel {

        public IsoLabel(int node, int edge, double weight, long time, double distance, IsoLabel parent) {
            this.node = node;
            this.edge = edge;
            this.weight = weight;
            this.time = time;
            this.distance = distance;
            this.parent = parent;
        }

        public boolean deleted = false;
        public int node;
        public int edge;
        public double weight;
        public long time;
        public double distance;
        public IsoLabel parent;

        @Override
        public String toString() {
            return "IsoLabel{" +
                    "node=" + node +
                    ", edge=" + edge +
                    ", weight=" + weight +
                    ", time=" + time +
                    ", distance=" + distance +
                    '}';
        }
    }

    private final IntObjectHashMap<IsoLabel> fromMap;
    private final PriorityQueue<IsoLabel> queueByWeighting;
    private int visitedNodes;
    private double limit = -1;
    private ExploreType exploreType = TIME;
    private final boolean reverseFlow;

    public ShortestPathTree(Graph g, Weighting weighting, boolean reverseFlow, TraversalMode traversalMode) {
        super(g, weighting, traversalMode);
        queueByWeighting = new PriorityQueue<>(1000, comparingDouble(l -> l.weight));
        fromMap = new GHIntObjectHashMap<>(1000);
        this.reverseFlow = reverseFlow;
    }

    @Override
    public Path calcPath(int from, int to) {
        throw new IllegalStateException("call search instead");
    }

    /**
     * Time limit in milliseconds
     */
    public void setTimeLimit(double limit) {
        exploreType = TIME;
        this.limit = limit;
    }

    /**
     * Distance limit in meter
     */
    public void setDistanceLimit(double limit) {
        exploreType = DISTANCE;
        this.limit = limit;
    }

    public void setWeightLimit(double limit) {
        exploreType = WEIGHT;
        this.limit = limit;
    }

    public void search(int from, final Consumer<IsoLabel> consumer) {
        List<Integer> fromList = new ArrayList<Integer>();
        fromList.add(from);
        search(false, fromList, consumer);
    }

    public void search(boolean useDistanceAsWeight, List<Integer> from, final Consumer<IsoLabel> consumer) {
        List<IsoLabel> fromLabels = new ArrayList<>();
        for (int node : from) {
            IsoLabel currentLabel = new IsoLabel(node, -1, 0, 0, 0, null);
            fromLabels.add(currentLabel);
        }
        searchFromLabels(useDistanceAsWeight, fromLabels, consumer);
    }

    public void searchFromLabels(boolean useDistanceAsWeight, List<IsoLabel> from, final Consumer<IsoLabel> consumer) {
        checkAlreadyRun();
        for (IsoLabel currentLabel : from) {
            queueByWeighting.add(currentLabel);
            if (traversalMode == TraversalMode.NODE_BASED) {
                fromMap.put(currentLabel.node, currentLabel);
                consumer.accept(currentLabel);
            }
        }
        while (!finished()) {
            IsoLabel currentLabel = queueByWeighting.poll();
            if (currentLabel.deleted)
                continue;
            currentLabel.deleted = true;
            visitedNodes++;

            EdgeIterator iter = edgeExplorer.setBaseNode(currentLabel.node);
            while (iter.next()) {
                if (!accept(iter, currentLabel.edge)) {
                    continue;
                }

                double nextWeight = GHUtility.calcWeightWithTurnWeightWithAccess(weighting, iter, reverseFlow, currentLabel.edge) + currentLabel.weight;
                if (Double.isInfinite(nextWeight))
                    continue;

                double nextDistance = iter.getDistance() + currentLabel.distance;
                if (Math.abs(currentLabel.distance-119.219604) < 1e-5) { // && Math.abs(nextDistance-168.41527) < 1e-5) {
                    logger.error("\n\n\nFound the bad edge");
                    logger.error("distance = " + Double.toString(iter.getDistance()));
                    IsoLabel node = currentLabel.parent;
                    while (node != null) {
                        double lat = this.graph.getNodeAccess().getLat(node.node);
                        double lon = this.graph.getNodeAccess().getLon(node.node);
                        logger.error("\n(" + Double.toString(lat) + ", " + Double.toString(lon) + ")");
                        logger.error("distance = " + Double.toString(node.distance));
                        logger.error("weight = " + Double.toString(node.weight));
                        node = node.parent;
                    }
                    logger.error("\n\n\n");
                }
                if (useDistanceAsWeight) {
                    nextWeight = nextDistance;
                }
                long nextTime = GHUtility.calcMillisWithTurnMillis(weighting, iter, reverseFlow, currentLabel.edge) + currentLabel.time;
                int nextTraversalId = traversalMode.createTraversalId(iter, reverseFlow);
                IsoLabel label = fromMap.get(nextTraversalId);
                IsoLabel newLabel = new IsoLabel(iter.getAdjNode(), iter.getEdge(), nextWeight, nextTime, nextDistance, currentLabel);
                consumer.accept(newLabel);
                if (label == null) {
                    label = newLabel;
                    fromMap.put(nextTraversalId, label);
                    if (getExploreValue(label) <= limit) {
                        queueByWeighting.add(label);
                    }
                } else if (label.weight > nextWeight) {
                    label.deleted = true;
                    label = newLabel;
                    fromMap.put(nextTraversalId, label);
                    if (getExploreValue(label) <= limit) {
                        queueByWeighting.add(label);
                    }
                }
            }
        }
    }

    public Collection<IsoLabel> getIsochroneEdges() {
        // assert alreadyRun
        ArrayList<IsoLabel> result = new ArrayList<>();
        for (ObjectCursor<IsoLabel> cursor : fromMap.values()) {
            if (getExploreValue(cursor.value) > limit) {
                assert cursor.value.parent == null || getExploreValue(cursor.value.parent) <= limit;
                result.add(cursor.value);
            }
        }
        return result;
    }

    private double getExploreValue(IsoLabel label) {
        if (exploreType == TIME)
            return label.time;
        if (exploreType == WEIGHT)
            return label.weight;
        return label.distance;
    }

    @Override
    protected boolean finished() {
        return queueByWeighting.isEmpty();
    }

    @Override
    protected Path extractPath() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getName() {
        return "reachability";
    }

    @Override
    public int getVisitedNodes() {
        return visitedNodes;
    }
}
