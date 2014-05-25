/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *   * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package edu.ucsb.cs.eager.sa;

import edu.ucsb.cs.eager.sa.loops.LoopBoundAnalysis;
import edu.ucsb.cs.eager.sa.loops.ai.IntegerInterval;
import soot.IntType;
import soot.Unit;
import soot.Value;
import soot.jimple.AssignStmt;
import soot.jimple.IntConstant;
import soot.jimple.Stmt;
import soot.jimple.toolkits.annotation.logic.Loop;
import soot.jimple.toolkits.annotation.logic.LoopFinder;
import soot.toolkits.graph.UnitGraph;

import java.util.*;

public class SimulationManager {

    private static final double LOOP_REPETITION_PROBABILITY = 0.9;

    private static final Random rand = new Random();

    private UnitGraph graph;
    private Collection<Loop> loops;
    private InstructionSimulator simulator;
    private Map<Value,IntegerInterval> variables = new HashMap<Value, IntegerInterval>();

    public SimulationManager(UnitGraph graph, InstructionSimulator simulator) {
        this.graph = graph;
        this.simulator = simulator;
        LoopFinder loopFinder = new LoopFinder();
        loopFinder.transform(graph.getBody());
        loops = loopFinder.loops();
    }

    public double simulate(int rounds, boolean verbose) {
        if (rounds == 0) {
            throw new IllegalArgumentException("Number of rounds must be > 0");
        }

        double[] results = new double[rounds];
        for (int i = 0; i < rounds; i++) {
            results[i] = doSimulate(graph);
            if (verbose) {
                System.out.println("Round-" + i + ": " + results[i]);
            }
        }
        return simulator.summarize(results);
    }

    private double doSimulate(UnitGraph graph) {
        double cost = simulator.getInitialValue();
        Unit current = null;
        while (true) {
            current = getNextInstruction(graph, current);
            if (current == null) {
                break;
            }

            double item = simulator.simulateInstruction((Stmt) current);
            handleVariables((Stmt) current);
            cost = simulator.aggregateInstructionResult(cost, item);
        }
        return cost;
    }

    private void handleVariables(Stmt stmt) {
        if (stmt instanceof AssignStmt) {
            AssignStmt assign = (AssignStmt) stmt;
            Value leftOp = assign.getLeftOp();
            Value rightOp = assign.getRightOp();
            if (rightOp instanceof IntConstant) {
                variables.put(leftOp, new IntegerInterval(((IntConstant) rightOp).value));
            }
        }
    }

    private Unit getNextInstruction(UnitGraph graph, Unit currentInstruction) {
        List<Unit> candidates;
        if (currentInstruction == null) {
            candidates = graph.getHeads();
        } else {
            candidates = new ArrayList<Unit>();
            candidates.addAll(graph.getSuccsOf(currentInstruction));
            Loop loop = findLoop(currentInstruction);
            if (loop != null) {
                System.out.println(LoopBoundAnalysis.estimateBound((Stmt) currentInstruction, loops, variables));
                Unit nextLoopInstruction = findNextLoopInstruction(loop, candidates);
                if (rand.nextDouble() <= LOOP_REPETITION_PROBABILITY) {
                    return nextLoopInstruction;
                } else {
                    candidates.remove(nextLoopInstruction);
                }
            }
        }

        if (candidates == null || candidates.size() == 0) {
            return null;
        } else if (candidates.size() == 1) {
            return candidates.get(0);
        } else {
            return simulator.getBranchSelector().select(currentInstruction, candidates);
        }
    }

    private Loop findLoop(Unit unit) {
        for (Loop loop : loops) {
            if (loop.getHead().equals(unit)) {
                return loop;
            }
        }
        return null;
    }

    private Unit findNextLoopInstruction(Loop loop, List<Unit> candidates) {
        for (Unit candidate : candidates) {
            if (loop.getLoopStatements().contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Failed to locate the next instruction from the loop");
    }
}
