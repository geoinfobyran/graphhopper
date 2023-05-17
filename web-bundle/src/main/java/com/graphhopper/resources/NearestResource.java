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
package com.graphhopper.resources;

import com.graphhopper.NearestRequest;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.DistanceCalc;
import com.graphhopper.util.DistanceCalcEarth;
import com.graphhopper.util.shapes.GHPoint;
import com.graphhopper.util.shapes.GHPoint3D;

import java.util.List;

import javax.inject.Inject;
import javax.validation.constraints.NotNull;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

/**
 * @author svantulden
 * @author Michael Zilske
 */
@Path("nearest")
@Produces(MediaType.APPLICATION_JSON)
public class NearestResource {

    private final DistanceCalc calc = DistanceCalcEarth.DIST_EARTH;
    private final LocationIndex index;

    @Inject
    NearestResource(LocationIndex index) {
        this.index = index;
    }

    public static class Response {
        public final double[] distances; // Distances from inputs to snapped points in meters

        @JsonCreator
        Response(@JsonProperty("distances") double[] distances) {
            this.distances = distances;
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response doPost(@NotNull NearestRequest request) {
        double[] distances = new double[request.getPoints().size()];
        List<GHPoint> points = request.getPoints();
        for (int i = 0; i < points.size(); i++) {
            GHPoint point = points.get(i);
            Snap snap = index.findClosest(point.lat, point.lon, EdgeFilter.ALL_EDGES);
            if (snap.isValid()) {
                GHPoint3D snappedPoint = snap.getSnappedPoint();
                distances[i] = calc.calcDist(point.lat, point.lon, snappedPoint.lat, snappedPoint.lon);
            } else {
                throw new WebApplicationException("Nearest point cannot be found!");
            }
        }
        return new Response(distances);
    }

}
