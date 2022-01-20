package com.graphhopper.resources;

import org.locationtech.jts.geom.Coordinate;

public class CoordinateWithTime implements Comparable<CoordinateWithTime> {
    public final double lat;
    public final double lng;
    public final double time_in_seconds;

    public CoordinateWithTime(Coordinate coordinate, double time_in_seconds) {
        this.lat = coordinate.y;
        this.lng = coordinate.x;
        this.time_in_seconds = time_in_seconds;
    }

    public CoordinateWithTime(double lat, double lng, double time_in_seconds) {
        this.lat = lat;
        this.lng = lng;
        this.time_in_seconds = time_in_seconds;
    }

    @Override
    public int compareTo(CoordinateWithTime other) {
        return Double.compare(time_in_seconds, other.time_in_seconds);
    }
}