package com.alruwaili.dalilalbar.nav

import org.maplibre.android.geometry.LatLng
import java.util.PriorityQueue

/**
 * بحث أقصر مسار (خوارزمية Dijkstra القياسية) على الرسم البياني الحقيقي لشبكة الطرق/الدروب.
 * لا تخمين هنا إطلاقاً: المسافات محسوبة من إحداثيات العقد الحقيقية، والتصنيف (معبّد/درب) من
 * وسوم OpenStreetMap الفعلية التي حُمّلت عبر Overpass API.
 */
object RoutePlanner {

    /** نتيجة المسار: نقاطه بالترتيب، وتصنيف كل قطعة بين نقطتين متتاليتين (بطول points.size - 1). */
    class PathResult(
        val points: List<LatLng>,
        val edgeClasses: List<RoadClass>,
        val distanceMeters: Double
    )

    fun shortestPath(graph: RoadGraph, startNodeId: Long, endNodeId: Long): PathResult? {
        if (startNodeId == endNodeId) {
            val p = graph.nodeLatLng[startNodeId] ?: return null
            return PathResult(listOf(p), emptyList(), 0.0)
        }

        data class QEntry(val nodeId: Long, val cost: Double)

        val dist = HashMap<Long, Double>()
        val prevNode = HashMap<Long, Long>()
        val arrivingEdgeClass = HashMap<Long, RoadClass>()
        val visited = HashSet<Long>()
        val pq = PriorityQueue<QEntry>(compareBy { it.cost })

        dist[startNodeId] = 0.0
        pq.add(QEntry(startNodeId, 0.0))

        while (pq.isNotEmpty()) {
            val current = pq.poll()
            val nodeId = current.nodeId
            if (!visited.add(nodeId)) continue
            if (nodeId == endNodeId) break

            val edges = graph.adjacency[nodeId] ?: continue
            val baseCost = dist[nodeId] ?: continue
            for (edge in edges) {
                if (edge.toNodeId in visited) continue
                val edgeWeight = if (edge.roadClass == RoadClass.TRACK) {
                    edge.distanceMeters * TRACK_WEIGHT_PENALTY
                } else {
                    edge.distanceMeters
                }
                val newCost = baseCost + edgeWeight
                if (newCost < (dist[edge.toNodeId] ?: Double.MAX_VALUE)) {
                    dist[edge.toNodeId] = newCost
                    prevNode[edge.toNodeId] = nodeId
                    arrivingEdgeClass[edge.toNodeId] = edge.roadClass
                    pq.add(QEntry(edge.toNodeId, newCost))
                }
            }
        }

        if (endNodeId !in dist) return null

        val pathNodeIds = ArrayList<Long>()
        var cursor: Long? = endNodeId
        while (cursor != null) {
            pathNodeIds.add(cursor)
            cursor = prevNode[cursor]
        }
        pathNodeIds.reverse()

        val points = ArrayList<LatLng>(pathNodeIds.size)
        val classes = ArrayList<RoadClass>(maxOf(0, pathNodeIds.size - 1))
        var realDistance = 0.0
        for (i in pathNodeIds.indices) {
            val nodeId = pathNodeIds[i]
            val p = graph.nodeLatLng[nodeId] ?: return null
            points.add(p)
            if (i > 0) {
                val cls = arrivingEdgeClass[nodeId] ?: RoadClass.PAVED
                classes.add(cls)
                val prevP = points[i - 1]
                realDistance += GeoMath.distanceMeters(prevP, p)
            }
        }
        return PathResult(points, classes, realDistance)
    }

    /** عقوبة وزن إضافية (وليست مسافة حقيقية مختلفة) للدروب، كي يُفضَّل الطريق المعبّد عند توفّر الاثنين. */
    private const val TRACK_WEIGHT_PENALTY = 1.6
}
