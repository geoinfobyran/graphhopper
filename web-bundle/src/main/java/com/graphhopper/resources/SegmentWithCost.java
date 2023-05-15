package com.graphhopper.resources;

public class SegmentWithCost implements Comparable<SegmentWithCost> {
    public final CoordinateWithCost from;
    public final CoordinateWithCost to;

    public SegmentWithCost(CoordinateWithCost from, CoordinateWithCost to) {
        this.from = from;
        this.to = to;
    }

    @Override
    public int compareTo(SegmentWithCost other) {
        int x = this.to.compareTo(other.to);
        if (x != 0) {
            return x;
        }
        return this.from.compareTo(other.from);
    }
}