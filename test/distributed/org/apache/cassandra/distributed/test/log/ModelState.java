/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.distributed.test.log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import static org.apache.cassandra.harry.model.TokenPlacementModel.DCReplicas;
import static org.apache.cassandra.harry.model.TokenPlacementModel.Node;
import static org.apache.cassandra.harry.model.TokenPlacementModel.NodeFactory;
import static org.apache.cassandra.harry.model.TokenPlacementModel.ReplicationFactor;

public class ModelState
{
    public final int maxClusterSize;
    public final int maxConcurrency;
    public final int uniqueNodes;
    public final int rejected;
    public final int[] cancelled;
    public final int[] finished;
    public final int bootstrappingCount;
    public final List<Node> currentNodes;
    public final Map<String, List<Node>> nodesByDc;
    public final List<Node> registeredNodes;
    public final List<Node> leavingNodes;
    public final List<Node> movingNodes;
    public final List<SimulatedOperation> inFlightOperations;
    public final PlacementSimulator.SimulatedPlacements simulatedPlacements;
    public final NodeFactory nodeFactory;


    public static ModelState empty(NodeFactory nodeFactory, int maxClusterSize, int maxConcurrency)
    {
        return new ModelState(maxClusterSize, maxConcurrency,
                              0, 0,
                              new int[]{ 0, 0, 0, 0 },
                              new int[]{ 0, 0, 0, 0 },
                              Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                              null,
                              nodeFactory);
    }

    public static Map<String, List<Node>> groupByDc(List<Node> nodes)
    {
        // using treemap here since it is much easier to read/debug when it comes to that
        Map<String, List<Node>> grouped = new TreeMap<>();
        for (Node node : nodes)
        {
            grouped.computeIfAbsent(node.dc(), (k) -> new ArrayList<>())
                   .add(node);
        }
        return grouped;
    }

    private ModelState(int maxClusterSize,
                       int maxConcurrency,
                       int uniqueNodes,
                       int rejected,
                       int[] cancelled,
                       int[] finished,
                       List<Node> currentNodes,
                       List<Node> registeredNodes,
                       List<Node> leavingNodes,
                       List<Node> movingNodes,
                       List<SimulatedOperation> operationStates,
                       PlacementSimulator.SimulatedPlacements simulatedPlacements,
                       NodeFactory nodeFactory)
    {
        this.maxClusterSize = maxClusterSize;
        this.maxConcurrency = maxConcurrency;
        this.uniqueNodes = uniqueNodes;
        this.rejected = rejected;
        this.cancelled = cancelled;
        this.finished = finished;
        this.currentNodes = currentNodes;
        this.registeredNodes = registeredNodes;
        this.nodesByDc = groupByDc(currentNodes);
        this.leavingNodes = leavingNodes;
        this.movingNodes = movingNodes;
        this.inFlightOperations = operationStates;
        this.simulatedPlacements = simulatedPlacements;
        bootstrappingCount = (int) operationStates.stream()
                                                  .filter(s -> s.type == SimulatedOperation.Type.JOIN)
                                                  .count();
        this.nodeFactory = nodeFactory;
    }

    public Transformer transformer()
    {
        return new Transformer(this);
    }

    private boolean withinConcurrencyLimit()
    {
        return inFlightOperations.size() < maxConcurrency;
    }

    public boolean shouldBootstrap()
    {
        return withinConcurrencyLimit() && bootstrappingCount + currentNodes.size() < maxClusterSize;
    }

    public boolean shouldLeave(ReplicationFactor rf, Random rng)
    {
        return canRemove(rf) && rng.nextDouble() > 0.7;
    }

    public boolean shouldMove(ReplicationFactor rf, Random rng)
    {
        return canRemove(rf) && rng.nextDouble() > 0.7;
    }

    public boolean shouldReplace(ReplicationFactor rf, Random rng)
    {
        return canRemove(rf) && rng.nextDouble() > 0.8;
    }

    private boolean canRemove(ReplicationFactor rfs)
    {
        if (!withinConcurrencyLimit()) return false;
        for (Map.Entry<String, DCReplicas> e : rfs.asMap().entrySet())
        {
            String dc = e.getKey();
            int rf = e.getValue().totalCount;
            List<Node> nodes = nodesByDc.get(dc);
            Set<Node> nodesInDc = nodes == null ? new HashSet<>() : new HashSet<>(nodes);
            for (SimulatedOperation op : inFlightOperations)
                nodesInDc.removeAll(Arrays.asList(op.nodes));
            if (nodesInDc.size() > rf)
                return true;
        }
        return false;
    }

    public boolean shouldCancel(Random rng)
    {
        return rng.nextDouble() > 0.95;
    }

    public String toString()
    {
        return "ModelState{" +
               "uniqueNodes=" + uniqueNodes +
               ", rejectedOps=" + rejected +
               ", cancelledOps=" + Arrays.toString(cancelled) +
               ", finishedOps=" + Arrays.toString(finished) +
               ", bootstrappedNodes=" + currentNodes +
               ", leavingNodes=" + leavingNodes +
               ", operationStates=" + inFlightOperations +
               ", maxClusterSize=" + maxClusterSize +
               ", maxConcurrency=" + maxConcurrency +
               '}';
    }

    public static class Transformer
    {
        private final int maxClusterSize;
        private final int maxConcurrency;
        private int uniqueNodes;
        private int rejected;
        // join/replace/leave/move
        private int[] cancelled;
        private int[] finished;
        private List<Node> currentNodes;
        private List<Node> registeredNodes;
        private List<Node> leavingNodes;
        private List<Node> movingNodes;
        private List<SimulatedOperation> operationStates;
        private PlacementSimulator.SimulatedPlacements simulatedPlacements;
        private NodeFactory nodeFactory;

        private Transformer(ModelState source)
        {
            this.maxClusterSize = source.maxClusterSize;
            this.maxConcurrency = source.maxConcurrency;
            this.uniqueNodes = source.uniqueNodes;
            this.rejected = source.rejected;
            this.cancelled = source.cancelled;
            this.finished = source.finished;
            this.currentNodes = source.currentNodes;
            this.registeredNodes = source.registeredNodes;
            this.leavingNodes = source.leavingNodes;
            this.movingNodes = source.movingNodes;
            this.operationStates = source.inFlightOperations;
            this.simulatedPlacements = source.simulatedPlacements;
            this.nodeFactory = source.nodeFactory;
        }

        public Transformer incrementUniqueNodes()
        {
            uniqueNodes++;
            return this;
        }

        public Transformer incrementRejected()
        {
            rejected++;
            return this;
        }

        public Transformer incrementCancelledJoin()
        {
            cancelled[0]++;
            return this;
        }

        public Transformer incrementCancelledReplace()
        {
            cancelled[1]++;
            return this;
        }

        public Transformer incrementCancelledLeave()
        {
            cancelled[2]++;
            return this;
        }

        public Transformer incrementCancelledMove()
        {
            cancelled[3]++;
            return this;
        }

        public Transformer addOperation(SimulatedOperation operation)
        {
            operationStates = new ArrayList<>(operationStates);
            operationStates.add(operation);
            return this;
        }

        public Transformer removeOperation(SimulatedOperation operation)
        {
            operationStates = new ArrayList<>(operationStates);
            operationStates.remove(operation);
            return this;
        }

        public Transformer withJoined(Node node)
        {
            addToCluster(node);
            finished[0]++;
            return this;
        }

        public Transformer recycleRejected(Node node)
        {
            registeredNodes = new ArrayList<>(registeredNodes);
            registeredNodes.add(node);
            return this;
        }

        public Transformer withMoved(Node movingNode, Node movedTo)
        {
            assert currentNodes.contains(movingNode) : movingNode;
            List<Node> tmp = currentNodes;
            currentNodes = new ArrayList<>();
            for (Node n : tmp)
            {
                if (n.idx() == movingNode.idx())
                    currentNodes.add(movedTo);
                else
                    currentNodes.add(n);
            }
            finished[3]++;

            assert movingNodes.contains(movingNode);
            movingNodes = new ArrayList<>(movingNodes);
            movingNodes.remove(movingNode);
            return this;
        }

        private void addToCluster(Node node)
        {
            // called during both join and replacement
            currentNodes = new ArrayList<>(currentNodes);
            currentNodes.add(node);
        }

        public Transformer markMoving(Node moving)
        {
            assert currentNodes.contains(moving);
            movingNodes = new ArrayList<>(movingNodes);
            movingNodes.add(moving);
            return this;
        }

        public Transformer markLeaving(Node leaving)
        {
            assert currentNodes.contains(leaving);
            leavingNodes = new ArrayList<>(leavingNodes);
            leavingNodes.add(leaving);
            return this;
        }

        public Transformer withLeft(Node node)
        {
            assert currentNodes.contains(node);
            // for now... assassinate may change this assertion
            assert leavingNodes.contains(node);
            finished[2]++;
            removeFromCluster(node);
            return this;
        }

        private void removeFromCluster(Node node)
        {
            // called during both decommission and replacement
            currentNodes = new ArrayList<>(currentNodes);
            currentNodes.remove(node);
            leavingNodes = new ArrayList<>(leavingNodes);
            leavingNodes.remove(node);
        }

        public Transformer withReplaced(Node oldNode, Node newNode)
        {
            addToCluster(newNode);
            removeFromCluster(oldNode);
            finished[1]++;
            return this;
        }

        public Transformer updateSimulation(PlacementSimulator.SimulatedPlacements simulatedPlacements)
        {
            this.simulatedPlacements = simulatedPlacements;
            return this;
        }

        public ModelState transform()
        {
            return new ModelState(maxClusterSize,
                                  maxConcurrency,
                                  uniqueNodes,
                                  rejected,
                                  cancelled,
                                  finished,
                                  currentNodes,
                                  registeredNodes,
                                  leavingNodes,
                                  movingNodes,
                                  operationStates,
                                  simulatedPlacements,
                                  nodeFactory);
        }
    }
}