package org.apache.tinkerpop.gremlin.tinkergraph.structure;

import java.util.HashSet;
import java.util.Set;

/**
 * A 1D Interval Tree augmented to map temporal intervals [start, end] to TinkerPop Elements.
 * This structure allows O(log N) global discovery of elements that overlap with a temporal query.
 */
public class IntervalTree<T> {

    private class Node {
        long start;
        long end;
        long maxEnd;
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

    private Node root;

    /**
     * Inserts an element associated with a temporal interval into the tree.
     */
    public void put(long start, long end, T value) {
        root = insert(root, start, end, value);
    }

    private Node insert(Node node, long start, long end, T value) {
        if (node == null) {
            return new Node(start, end, value);
        }

        if (start == node.start && end == node.end) {
            node.values.add(value);
        } else if (start < node.start) {
            node.left = insert(node.left, start, end, value);
        } else {
            node.right = insert(node.right, start, end, value);
        }

        updateMaxEnd(node);
        return node;
    }

    /**
     * Searches for all elements whose interval intersects with the query window [queryStart, queryEnd].
     * Runs in O(R + log N) where R is the number of intersecting elements.
     */
    public Set<T> searchOverlaps(long queryStart, long queryEnd) {
        Set<T> result = new HashSet<>();
        searchOverlaps(root, queryStart, queryEnd, result);
        return result;
    }

    private void searchOverlaps(Node node, long start, long end, Set<T> result) {
        if (node == null) return;

        // If the query interval is completely to the right of the maximum end in this subtree,
        // then there are no overlaps in this subtree.
        if (start > node.maxEnd) return;

        // Check if current node's interval overlaps the query
        if (node.start <= end && node.end >= start) {
            result.addAll(node.values);
        }

        // Always check the left child if it could contain overlaps
        if (node.left != null) {
            searchOverlaps(node.left, start, end, result);
        }

        // We only need to search the right child if the query end extends past the current node's start
        if (node.right != null && end >= node.start) {
            searchOverlaps(node.right, start, end, result);
        }
    }

    /**
     * Removes an element's association with a temporal interval.
     */
    public void remove(long start, long end, T value) {
        root = remove(root, start, end, value);
    }

    private Node remove(Node node, long start, long end, T value) {
        if (node == null) return null;
        
        if (start == node.start && end == node.end) {
            node.values.remove(value);
        } else if (start < node.start) {
            node.left = remove(node.left, start, end, value);
        } else {
            node.right = remove(node.right, start, end, value);
        }

        updateMaxEnd(node);
        return node;
    }

    private void updateMaxEnd(Node node) {
        node.maxEnd = node.end;
        if (node.left != null && node.maxEnd < node.left.maxEnd) node.maxEnd = node.left.maxEnd;
        if (node.right != null && node.maxEnd < node.right.maxEnd) node.maxEnd = node.right.maxEnd;
    }
}
