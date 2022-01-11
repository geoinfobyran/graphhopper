package com.graphhopper.resources;

public class SegmentWithTime implements Comparable<SegmentWithTime> {
    public final CoordinateWithTime from;
    public final CoordinateWithTime to;

    public SegmentWithTime(CoordinateWithTime from, CoordinateWithTime to) {
        this.from = from;
        this.to = to;
    }

    @Override
    public int compareTo(SegmentWithTime other) {
        int x = this.to.compareTo(other.to);
        if (x != 0) {
            return x;
        }
        return this.from.compareTo(other.from);
    }
}