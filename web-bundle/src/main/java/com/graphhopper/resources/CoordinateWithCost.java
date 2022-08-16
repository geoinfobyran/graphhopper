package com.graphhopper.resources;

import org.locationtech.jts.geom.Coordinate;

public class CoordinateWithCost implements Comparable<CoordinateWithCost> {
    public final double lat;
    public final double lng;
    public final double cost;

    public CoordinateWithCost(Coordinate coordinate, double cost) {
        this.lat = coordinate.y;
        this.lng = coordinate.x;
        this.cost = cost;
    }

    public CoordinateWithCost(double lat, double lng, double cost) {
        this.lat = lat;
        this.lng = lng;
        this.cost = cost;
    }

    @Override
    public int compareTo(CoordinateWithCost other) {
        return Double.compare(cost, other.cost);
    }
}