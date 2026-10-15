package org.apache.tinkerpop.gremlin.tinkergraph.structure;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * An AVL-balanced interval tree that maps closed temporal intervals {@code [start, end]} to values (TinkerPop
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
     * Searches for all elements whose interval intersects with the query window [queryStart, queryEnd]
     * (inclusive on both sides).
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

    private void searchOverlaps(Node node, long start, long end, Set<T> result) {
        if (node == null) return;

        // If the query interval is completely to the right of the maximum end in this subtree,
        // then there are no overlaps in this subtree.
        if (start > node.maxEnd) return;

        searchOverlaps(node.left, start, end, result);

        if (node.start <= end && node.end >= start) {
            result.addAll(node.values);
        }

        // The right subtree only holds intervals starting at or after node.start
        if (end >= node.start) {
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
