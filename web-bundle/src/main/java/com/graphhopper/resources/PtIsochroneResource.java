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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.inject.Inject;
import javax.validation.constraints.NotNull;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import com.conveyal.gtfs.GTFSFeed;
import com.graphhopper.gtfs.GHLocation;
import com.graphhopper.gtfs.GHPointLocation;
import com.graphhopper.gtfs.GHStationLocation;
import com.graphhopper.gtfs.GraphExplorer;
import com.graphhopper.gtfs.GtfsStorage;
import com.graphhopper.gtfs.Label;
import com.graphhopper.gtfs.MultiCriteriaLabelSetting;
import com.graphhopper.gtfs.PtEncodedValues;
import com.graphhopper.gtfs.RealtimeFeed;
import com.graphhopper.gtfs.GtfsStorage.EdgeType;
import com.graphhopper.gtfs.Label.EdgeLabel;
import com.graphhopper.http.GHLocationParam;
import com.graphhopper.http.OffsetDateTimeParam;
import com.graphhopper.jackson.ResponsePathSerializer;
import com.graphhopper.routing.ev.Subnetwork;
import com.graphhopper.routing.querygraph.QueryGraph;
import com.graphhopper.routing.util.DefaultSnapFilter;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.weighting.FastestWeighting;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.JsonFeature;
import com.graphhopper.util.exceptions.PointNotFoundException;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("isochrone-pt")
public class PtIsochroneResource {

    private static final Logger logger = LoggerFactory.getLogger(PtIsochroneResource.class);

    private static final double JTS_TOLERANCE = 0.00001;

    private final GtfsStorage gtfsStorage;
    private final EncodingManager encodingManager;
    private final GraphHopperStorage graphHopperStorage;
    private final LocationIndex locationIndex;

    @Inject
    public PtIsochroneResource(GtfsStorage gtfsStorage, EncodingManager encodingManager,
            GraphHopperStorage graphHopperStorage, LocationIndex locationIndex) {
        this.gtfsStorage = gtfsStorage;
        this.encodingManager = encodingManager;
        this.graphHopperStorage = graphHopperStorage;
        this.locationIndex = locationIndex;
    }

    public static class BaseResponse {

    }

    public static class Response extends BaseResponse {
        public static class Info {
            public List<String> copyrights = new ArrayList<>();
        }

        public List<JsonFeature> polygons = new ArrayList<>();
        public Info info = new Info();
    }

    public static class ResponseWithTimes extends BaseResponse {
        public SegmentWithCost[] segments;
    }

    @GET
    @Produces({ MediaType.APPLICATION_JSON })
    public BaseResponse doGet(
            @QueryParam("point") GHLocationParam sourceParam,
            @QueryParam("time_limit") @DefaultValue("600") long seconds,
            @QueryParam("reverse_flow") @DefaultValue("false") boolean reverseFlow,
            @QueryParam("pt.earliest_departure_time") @NotNull OffsetDateTimeParam departureTimeParam,
            @QueryParam("pt.blocked_route_types") @DefaultValue("0") int blockedRouteTypes,
            @QueryParam("result") @DefaultValue("multipolygon") String format) {
        Instant initialTime = departureTimeParam.get().toInstant();
        GHLocation location = sourceParam.get();

        double targetZ = seconds * 1000;

        final FlagEncoder footEncoder = encodingManager.getEncoder("foot");
        final Weighting weighting = new FastestWeighting(footEncoder);

        Snap snap = findByPointOrStation(location, weighting);
        QueryGraph queryGraph = QueryGraph.create(graphHopperStorage, Collections.singletonList(snap));
        if (!snap.isValid()) {
            throw new PointNotFoundException("Cannot find location: " + location, 0);
        }

        PtEncodedValues ptEncodedValues = PtEncodedValues.fromEncodingManager(encodingManager);
        GraphExplorer graphExplorer = new GraphExplorer(queryGraph, weighting, ptEncodedValues, gtfsStorage,
                RealtimeFeed.empty(gtfsStorage), reverseFlow, false, false, 5.0, reverseFlow, blockedRouteTypes);
        MultiCriteriaLabelSetting router = new MultiCriteriaLabelSetting(graphExplorer, ptEncodedValues, reverseFlow,
                false, false, 0, Collections.emptyList());

        Map<Coordinate, SegmentWithCost> z1 = new HashMap<>();
        NodeAccess nodeAccess = queryGraph.getNodeAccess();

        Instant start = Instant.now();
        RealtimeFeed realtimeFeed = RealtimeFeed.empty(gtfsStorage);
        MultiCriteriaLabelSetting.SPTVisitor sptVisitor = nodeLabel -> {
            if (nodeLabel.parent != null) {
                EdgeIteratorState edgeIteratorState = queryGraph.getEdgeIteratorState(nodeLabel.edge, nodeLabel.parent.adjNode).detach(false);
                EdgeLabel transition = Label.getEdgeLabel(edgeIteratorState, ptEncodedValues, realtimeFeed);
                if (transition.edgeType == EdgeType.HIGHWAY) {
                    Coordinate nodeCoordinate = new Coordinate(nodeAccess.getLon(nodeLabel.adjNode),
                            nodeAccess.getLat(nodeLabel.adjNode));
                    CoordinateWithCost to = new CoordinateWithCost(
                            nodeCoordinate,
                            (double) (nodeLabel.currentTime - initialTime.toEpochMilli()) * (reverseFlow ? -1 : 1)
                                    / 1000.0);
                    CoordinateWithCost from = new CoordinateWithCost(
                            new Coordinate(nodeAccess.getLon(nodeLabel.parent.adjNode),
                                    nodeAccess.getLat(nodeLabel.parent.adjNode)),
                            (double) (nodeLabel.parent.currentTime - initialTime.toEpochMilli()) * (reverseFlow ? -1 : 1)
                                    / 1000.0);
                    z1.merge(nodeCoordinate, new SegmentWithCost(from, to), (a, b) -> a.compareTo(b) < 0 ? a : b);
                }
            }
        };

        calcLabels(router, snap.getClosestNode(), initialTime, sptVisitor,
                label -> (label.currentTime - initialTime.toEpochMilli()) * (reverseFlow ? -1 : 1) <= targetZ);
        Instant end = Instant.now();
        Duration timeElapsed = Duration.between(start, end);
        System.out.println("Graph search: " + timeElapsed.toMillis() + " milliseconds");
        return wrapNodesWithTimes(z1.values().toArray(new SegmentWithCost[0]));
    }

    private Snap findByPointOrStation(GHLocation location, Weighting weighting) {
        if (location instanceof GHPointLocation) {
            final EdgeFilter filter = new DefaultSnapFilter(weighting,
                    encodingManager.getBooleanEncodedValue(Subnetwork.key("foot")));
            return locationIndex.findClosest(((GHPointLocation) location).ghPoint.lat,
                    ((GHPointLocation) location).ghPoint.lon, filter);
        } else if (location instanceof GHStationLocation) {
            for (Map.Entry<String, GTFSFeed> entry : gtfsStorage.getGtfsFeeds().entrySet()) {
                final Integer node = gtfsStorage.getStationNodes()
                        .get(new GtfsStorage.FeedIdWithStopId(entry.getKey(), ((GHStationLocation) location).stop_id));
                if (node != null) {
                    Snap snap = new Snap(graphHopperStorage.getNodeAccess().getLat(node),
                            graphHopperStorage.getNodeAccess().getLon(node));
                    snap.setSnappedPosition(Snap.Position.TOWER);
                    snap.setClosestNode(node);
                    return snap;
                }
            }
            throw new PointNotFoundException("Cannot find station: " + ((GHStationLocation) location).stop_id, 0);
        } else {
            throw new RuntimeException();
        }
    }

    private static void calcLabels(MultiCriteriaLabelSetting router, int from, Instant startTime,
            MultiCriteriaLabelSetting.SPTVisitor visitor, Predicate<Label> predicate) {
        Iterator<Label> iterator = router.calcLabels(from, startTime).iterator();
        while (iterator.hasNext()) {
            Label label = iterator.next();
            if (!predicate.test(label)) {
                break;
            }
            visitor.visit(label);
        }
    }

    private ResponseWithTimes wrapNodesWithTimes(SegmentWithCost[] segments) {
        Arrays.sort(segments);
        ResponseWithTimes response = new ResponseWithTimes();
        response.segments = segments;
        return response;
    }

}
