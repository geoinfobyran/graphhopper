package com.graphhopper.resources;

import com.graphhopper.GraphHopper;
import com.graphhopper.IsochroneRequest;
import com.graphhopper.Region;
import com.graphhopper.config.Profile;
import com.graphhopper.http.ProfileResolver;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.isochrone.algorithm.ShortestPathTree;
import com.graphhopper.isochrone.algorithm.ShortestPathTree.IsoLabel;
import com.graphhopper.isochrone.algorithm.Triangulator;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.Subnetwork;
import com.graphhopper.routing.querygraph.QueryGraph;
import com.graphhopper.routing.util.DefaultSnapFilter;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.*;
import com.graphhopper.util.DistanceCalcEarth;
import com.graphhopper.util.shapes.BBox;
import com.graphhopper.util.shapes.GHPoint;
import com.graphhopper.util.shapes.GHPoint3D;
import com.graphhopper.util.shapes.Polygon;

import org.locationtech.jts.geom.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.validation.constraints.NotNull;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

import java.util.function.ToDoubleFunction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static com.graphhopper.routing.util.TraversalMode.EDGE_BASED;
import static com.graphhopper.routing.util.TraversalMode.NODE_BASED;

@Path("isochrone")
public class IsochroneResource {

    private static final Logger logger = LoggerFactory.getLogger(IsochroneResource.class);

    private final GraphHopper graphHopper;

    @Inject
    public IsochroneResource(GraphHopper graphHopper) {
        this.graphHopper = graphHopper;
    }

    public enum ResponseType {
        json, geojson
    }

    public static class ResponseWithCosts {
        public SegmentWithCost[] segments;
    }

    private class PointWithEdge {
        int nodeId;
        int edgeId;

        PointWithEdge(int nodeId, int edgeId) {
            this.nodeId = nodeId;
            this.edgeId = edgeId;
        }
    }

    private List<PointWithEdge> getPointsInPolygon(List<GHPoint> points, LocationIndex locationIndex,
            NodeAccess nodeAccess) {
        List<PointWithEdge> pointsInPolygon = new ArrayList<>();
        if (points.size() <= 1) {
            return pointsInPolygon;
        }
        double[] lats = new double[points.size()];
        double[] lons = new double[points.size()];
        for (int i = 0; i < points.size(); i += 1) {
            GHPoint point = points.get(i);
            lats[i] = point.lat;
            lons[i] = point.lon;
        }
        Polygon polygon = new Polygon(lats, lons);
        BBox bbox = polygon.getBounds();
        locationIndex.query(bbox, edgeId -> {
            EdgeIteratorState edge = graphHopper.getBaseGraph().getEdgeIteratorStateForKey(edgeId * 2);
            for (int i = 0; i < 2; i++) {
                int nodeId = i == 0 ? edge.getBaseNode() : edge.getAdjNode();
                double lat = nodeAccess.getLat(nodeId);
                double lon = nodeAccess.getLon(nodeId);
                if (polygon.contains(lat, lon)) {
                    pointsInPolygon.add(new PointWithEdge(nodeId, edgeId));
                }
            }
        });
        return pointsInPolygon;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public ResponseWithCosts doPost(@NotNull IsochroneRequest request) {
        StopWatch sw = new StopWatch().start();
        DistanceCalcEarth distanceCalculator = new DistanceCalcEarth();
        PMap hintsMap = new PMap();
        hintsMap.putObject(Parameters.CH.DISABLE, true);
        hintsMap.putObject(Parameters.Landmark.DISABLE, true);

        Profile profile = graphHopper.getProfile(request.getProfileName());
        if (profile == null)
            throw new IllegalArgumentException(
                    "The requested profile '" + request.getProfileName() + "' does not exist");
        LocationIndex locationIndex = graphHopper.getLocationIndex();
        BaseGraph graph = graphHopper.getBaseGraph();
        Weighting weighting = graphHopper.createWeighting(profile, hintsMap);
        BooleanEncodedValue inSubnetworkEnc = graphHopper.getEncodingManager()
                .getBooleanEncodedValue(Subnetwork.key(request.getProfileName()));
        List<Snap> snaps = new ArrayList<Snap>();
        final NodeAccess nodeAccess = graphHopper.getBaseGraph().getNodeAccess();
        for (Region region : request.getRegions()) {
            List<GHPoint> points = region.getPoints();
            assert (!points.isEmpty());
            double[] lats = new double[points.size()];
            double[] lons = new double[points.size()];
            for (int i = 0; i < points.size(); i += 1) {
                GHPoint point = points.get(i);
                lats[i] = point.lat;
                lons[i] = point.lon;

                GHPoint nextPoint = points.get(i == points.size() - 1 ? 0 : (i + 1));
                int numSubsegments = (int) (Math
                        .ceil((Math.abs(point.lat - nextPoint.lat) + Math.abs(point.lon - nextPoint.lon)) / 1e-4)) + 1;
                for (int j = 0; j < numSubsegments; j++) {
                    double lat = ((point.lat * j) + nextPoint.lat * (numSubsegments - j)) / numSubsegments;
                    double lon = ((point.lon * j) + nextPoint.lon * (numSubsegments - j)) / numSubsegments;
                    Snap snap = locationIndex.findClosest(lat, lon,
                            new DefaultSnapFilter(weighting, inSubnetworkEnc));
                    if (!snap.isValid())
                        throw new IllegalArgumentException("Point not found: (" + lat + ", " + lon + ")");
                    snaps.add(snap);
                }
            }
            HashSet<Integer> nodeIdsInsideHoles = new HashSet<Integer>();
            for (List<GHPoint> hole : region.getHoles()) {
                for (PointWithEdge point : getPointsInPolygon(hole, locationIndex, nodeAccess)) {
                    nodeIdsInsideHoles.add(point.nodeId);
                }
            }
            for (PointWithEdge point : getPointsInPolygon(points, locationIndex, nodeAccess)) {
                int nodeId = point.nodeId;
                if (!nodeIdsInsideHoles.contains(nodeId)) {
                    EdgeIteratorState edge = graphHopper.getBaseGraph().getEdgeIteratorStateForKey(point.edgeId * 2);
                    double lat = nodeAccess.getLat(nodeId);
                    double lon = nodeAccess.getLon(nodeId);
                    final Snap snap = new Snap(lat, lon);
                    snap.setQueryDistance(0);
                    snap.setClosestNode(nodeId);
                    snap.setClosestEdge(edge.detach(false));
                    snap.setSnappedPosition(Snap.Position.TOWER);
                    snap.setQueryDistance(0);
                    snap.setSnappedPoint(new GHPoint3D(lat, lon, 0));
                    snaps.add(snap);
                }
            }
        }
        QueryGraph queryGraph = QueryGraph.create(graph, snaps);
        TraversalMode traversalMode = profile.isTurnCosts() ? EDGE_BASED : NODE_BASED;
        ShortestPathTree shortestPathTree = new ShortestPathTree(queryGraph, queryGraph.wrapWeighting(weighting),
                false, traversalMode);

        double limit;
        double normalization_factor;
        ToDoubleFunction<ShortestPathTree.IsoLabel> fz;
        if (request.getDistanceLimitInMeters() > 0) {
            normalization_factor = 1.0;
            limit = request.getDistanceLimitInMeters();
            shortestPathTree.setDistanceLimit(limit);
            fz = l -> l.distance;
        } else {
            normalization_factor = 0.001;
            limit = request.getTimeLimitInSeconds() / normalization_factor;
            shortestPathTree.setTimeLimit(limit);
            fz = l -> l.time;
        }
        assert (limit > 0);
        ArrayList<Double> zs = new ArrayList<>();
        zs.add(limit);

        final NodeAccess na = queryGraph.getNodeAccess();
        HashMap<Integer, Double> nodeDistances = new HashMap<Integer, Double>();
        for (Snap snap : snaps) {
            int node = snap.getClosestNode();
            double distance = snap.getQueryDistance();
            if (nodeDistances.containsKey(node)) {
                nodeDistances.put(node, Math.min(nodeDistances.get(node), distance));
            } else {
                nodeDistances.put(node, distance);
            }
        }
        List<IsoLabel> fromLabels = new ArrayList<IsoLabel>();
        for (Map.Entry<Integer, Double> entry : nodeDistances.entrySet()) {
            int node = entry.getKey();
            double distance = entry.getValue();
            // TODO: Set the weight and the time to reasonable values, if needed.
            IsoLabel currentLabel = new IsoLabel(node, -1, 0, 0, distance, null);
            fromLabels.add(currentLabel);
        }
        Collection<Coordinate> sites = new ArrayList<>();
        Collection<SegmentWithCost> segments = new ArrayList<>();
        shortestPathTree.searchFromLabels(request.getUseDistanceAsWeight(), fromLabels, label -> {
            double exploreValue = fz.applyAsDouble(label);
            double lat = na.getLat(label.node);
            double lon = na.getLon(label.node);
            Coordinate site = new Coordinate(lon, lat);
            site.z = exploreValue;
            sites.add(site);

            if (label.parent != null) {
                double parentExploreValue = fz.applyAsDouble(label.parent);
                EdgeIteratorState edge = queryGraph.getEdgeIteratorState(label.edge, label.node);
                ArrayList<CoordinateWithCost> coordinates = new ArrayList<>();
                double c1 = parentExploreValue * normalization_factor;
                double c2 = exploreValue * normalization_factor;

                PointList points = edge.fetchWayGeometry(FetchMode.PILLAR_ONLY);
                double prevLat = na.getLat(label.parent.node);
                double prevLon = na.getLon(label.parent.node);
                double[] segmentLengths = new double[points.size() + 1];
                double totalLength = 0.0;
                for (int i = 0; i < points.size(); i++) {
                    segmentLengths[i] = distanceCalculator.calcDist(prevLat, prevLon,
                            points.getLat(i), points.getLon(i));
                    totalLength += segmentLengths[i];
                    prevLat = points.getLat(i);
                    prevLon = points.getLon(i);
                }
                segmentLengths[segmentLengths.length - 1] = distanceCalculator.calcDist(prevLat, prevLon, lat, lon);
                totalLength += segmentLengths[segmentLengths.length - 1];

                double traversedLength = 0;
                coordinates.add(new CoordinateWithCost(
                        na.getLat(label.parent.node),
                        na.getLon(label.parent.node),
                        c1));
                for (int i = 0; i < points.size(); i++) {
                    traversedLength += segmentLengths[i];
                    // Interpolate costs of pillar nodes using the Euclidean distances along the
                    // path.
                    coordinates.add(new CoordinateWithCost(
                            points.getLat(i),
                            points.getLon(i),
                            totalLength > 0 ? c1 + (c2 - c1) * traversedLength / totalLength : (i == 0 ? c1 : c2)));
                }
                coordinates.add(new CoordinateWithCost(
                        lat,
                        lon,
                        c2));
                for (int i = 0; i + 1 < coordinates.size(); i++) {
                    segments.add(new SegmentWithCost(coordinates.get(i), coordinates.get(i + 1)));
                }
            }
        });
        logger.info("took: " + sw.getSeconds() + ", visited nodes:" + shortestPathTree.getVisitedNodes());
        return wrapNodesWithCosts(segments.toArray(new SegmentWithCost[0]));
    }

    private ResponseWithCosts wrapNodesWithCosts(SegmentWithCost[] segments) {
        Arrays.sort(segments);
        ResponseWithCosts response = new ResponseWithCosts();
        response.segments = segments;
        return response;
    }

    /**
     * We want to specify a tolerance in something like meters, but we need it in
     * unprojected lat/lon-space.
     * This is more correct in some parts of the world, and in some directions, than
     * in others.
     *
     * @param distanceInMeters distance in meters
     * @return "distance" in degrees
     */
    static double degreesFromMeters(double distanceInMeters) {
        return distanceInMeters / DistanceCalcEarth.METERS_PER_DEGREE;
    }

}