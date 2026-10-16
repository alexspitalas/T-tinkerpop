/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.tinkergraph.structure;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * An AVL-balanced interval tree that maps half-open temporal intervals {@code [start, end)} to values (TinkerPop
 * elements). Nodes are ordered by {@code (start, end)} and augmented with the maximum {@code end} of their subtree,
 * which allows an overlap query to skip whole subtrees. Insertions and removals are O(log N) and an overlap query
 * is O(log N + R), where R is the number of reported values. The tree stays balanced for any insertion order, so
 * chronologically ordered inserts do not degrade it.
 * <p/>
 * All operations are thread-safe.
 */
public class IntervalTree<T> {

    private class Node {
        long start;
        long end;
        long maxEnd;
        int height = 1;
        Set<T> values;
        Node left;
        Node right;

        Node(long start, long end, T value) {
            this.start = start;
            this.end = end;
            this.maxEnd = end;
            this.values = new HashSet<>();
            this.values.add(value);
        }
    }

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private Node root;

    /**
     * Inserts an element associated with a temporal interval into the tree.
     */
    public void put(long start, long end, T value) {
        lock.writeLock().lock();
        try {
            root = insert(root, start, end, value);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes an element's association with a temporal interval. Nodes that no longer hold any value are deleted.
     */
    public void remove(long start, long end, T value) {
        lock.writeLock().lock();
        try {
            root = remove(root, start, end, value);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Searches for all elements whose interval intersects with the query window [queryStart, queryEnd)
     * under half-open semantics. If queryStart == queryEnd, it evaluates point containment.
     */
    public Set<T> searchOverlaps(long queryStart, long queryEnd) {
        final Set<T> result = new HashSet<>();
        lock.readLock().lock();
        try {
            searchOverlaps(root, queryStart, queryEnd, result);
        } finally {
            lock.readLock().unlock();
        }
        return result;
    }

    /**
     * The height of the tree, 0 when empty. Mainly useful to verify that the tree stays balanced.
     */
    int height() {
        lock.readLock().lock();
        try {
            return height(root);
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean isEmpty() {
        lock.readLock().lock();
        try {
            return root == null;
        } finally {
            lock.readLock().unlock();
        }
    }

    private static int compare(long start, long end, long otherStart, long otherEnd) {
        final int c = Long.compare(start, otherStart);
        return c != 0 ? c : Long.compare(end, otherEnd);
    }

    private Node insert(Node node, long start, long end, T value) {
        if (node == null) {
            return new Node(start, end, value);
        }

        final int c = compare(start, end, node.start, node.end);
        if (c == 0) {
            node.values.add(value);
            return node;
        } else if (c < 0) {
            node.left = insert(node.left, start, end, value);
        } else {
            node.right = insert(node.right, start, end, value);
        }

        return rebalance(node);
    }

    private Node remove(Node node, long start, long end, T value) {
        if (node == null) return null;

        final int c = compare(start, end, node.start, node.end);
        if (c < 0) {
            node.left = remove(node.left, start, end, value);
        } else if (c > 0) {
            node.right = remove(node.right, start, end, value);
        } else {
            node.values.remove(value);
            if (node.values.isEmpty()) {
                if (node.left == null) return node.right;
                if (node.right == null) return node.left;

                // two children: replace by the in-order successor
                final Node successor = min(node.right);
                node.start = successor.start;
                node.end = successor.end;
                node.values = successor.values;
                node.right = removeMin(node.right);
            }
        }

        return rebalance(node);
    }

    private Node min(Node node) {
        while (node.left != null) node = node.left;
        return node;
    }

    private Node removeMin(Node node) {
        if (node.left == null) return node.right;
        node.left = removeMin(node.left);
        return rebalance(node);
    }

    private static boolean overlaps(long nodeStart, long nodeEnd, long queryStart, long queryEnd) {
        if (queryStart == queryEnd) {
            // Point query
            if (nodeStart == nodeEnd) return nodeStart == queryStart;
            return nodeStart <= queryStart && queryStart < nodeEnd;
        }
        if (nodeStart == nodeEnd) {
            // Point node in tree
            return queryStart <= nodeStart && nodeStart < queryEnd;
        }
        // Standard half-open intervals [nodeStart, nodeEnd) and [queryStart, queryEnd)
        return nodeStart < queryEnd && queryStart < nodeEnd;
    }

    private void searchOverlaps(Node node, long start, long end, Set<T> result) {
        if (node == null) return;

        // Subtree pruning:
        // All intervals in this subtree end at or before node.maxEnd.
        // For an interval query (start < end), overlap requires start < interval.end <= node.maxEnd,
        // so if start >= node.maxEnd, no overlap can exist in this subtree.
        // For a point query (start == end), overlap requires start < interval.end (or start == interval.end for point nodes),
        // so if start > node.maxEnd, no overlap can exist.
        if (start > node.maxEnd || (start == node.maxEnd && start < end)) return;

        searchOverlaps(node.left, start, end, result);

        if (overlaps(node.start, node.end, start, end)) {
            result.addAll(node.values);
        }

        // The right subtree only holds intervals starting at or after node.start.
        // For an interval query (start < end), an overlap requires interval.start < end,
        // so we only recurse if end > node.start.
        // For a point query (start == end), an overlap requires interval.start <= end,
        // so we only recurse if end >= node.start.
        boolean canRightOverlap = (start == end) ? (end >= node.start) : (end > node.start);
        if (canRightOverlap) {
            searchOverlaps(node.right, start, end, result);
        }
    }

    private int height(Node node) {
        return node == null ? 0 : node.height;
    }

    private void update(Node node) {
        node.height = 1 + Math.max(height(node.left), height(node.right));
        node.maxEnd = node.end;
        if (node.left != null && node.maxEnd < node.left.maxEnd) node.maxEnd = node.left.maxEnd;
        if (node.right != null && node.maxEnd < node.right.maxEnd) node.maxEnd = node.right.maxEnd;
    }

    private Node rebalance(Node node) {
        update(node);
        final int balance = height(node.left) - height(node.right);
        if (balance > 1) {
            if (height(node.left.left) < height(node.left.right)) node.left = rotateLeft(node.left);
            return rotateRight(node);
        }
        if (balance < -1) {
            if (height(node.right.right) < height(node.right.left)) node.right = rotateRight(node.right);
            return rotateLeft(node);
        }
        return node;
    }

    private Node rotateRight(Node node) {
        final Node pivot = node.left;
        node.left = pivot.right;
        pivot.right = node;
        update(node);
        update(pivot);
        return pivot;
    }

    private Node rotateLeft(Node node) {
        final Node pivot = node.right;
        node.right = pivot.left;
        pivot.left = node;
        update(node);
        update(pivot);
        return pivot;
    }
}
