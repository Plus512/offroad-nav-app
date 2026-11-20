package com.alruwaili.dalilalbar.nav

import org.maplibre.android.geometry.LatLng

/** رسم بياني (graph) لشبكة الطرق/الدروب الحقيقية، مبني من بيانات Overpass الفعلية فقط. */
class RoadGraph(
    val nodeLatLng: Map<Long, LatLng>,
    val adjacency: Map<Long, List<Edge>>
) {
    data class Edge(val toNodeId: Long, val distanceMeters: Double, val roadClass: RoadClass)

    companion object {
        /**
         * يبني الرسم البياني من شبكة حقيقية.
         * @param includeTrack إن كان false يُقتصر على الطرق المعبّدة فقط (محاولة أولى)،
         *                     وإن كان true تُضاف الدروب (ردمية) أيضاً (محاولة ثانية عند فشل الأولى).
         */
        fun build(network: RoadNetwork, includeTrack: Boolean): RoadGraph {
            val nodeLatLng = HashMap<Long, LatLng>(network.nodes.size)
            for ((id, coord) in network.nodes) {
                nodeLatLng[id] = LatLng(coord[0], coord[1])
            }
            val adjacency = HashMap<Long, MutableList<Edge>>()

            fun addEdge(a: Long, b: Long, cls: RoadClass) {
                val pa = nodeLatLng[a] ?: return
                val pb = nodeLatLng[b] ?: return
                val dist = GeoMath.distanceMeters(pa, pb)
                if (dist <= 0.0) return
                adjacency.getOrPut(a) { ArrayList() }.add(Edge(b, dist, cls))
            }

            for (way in network.ways) {
                val cls = RoadClassifier.classify(way) ?: continue
                if (cls == RoadClass.TRACK && !includeTrack) continue
                if (way.nodeIds.size < 2) continue

                val isReversedOnly = way.oneway == "-1"
                val isOnewayForward = way.oneway == "yes" || way.oneway == "1" || way.oneway == "true"

                for (i in 0 until way.nodeIds.size - 1) {
                    val a = way.nodeIds[i]
                    val b = way.nodeIds[i + 1]
                    when {
                        isReversedOnly -> addEdge(b, a, cls)
                        isOnewayForward -> addEdge(a, b, cls)
                        else -> {
                            addEdge(a, b, cls)
                            addEdge(b, a, cls)
                        }
                    }
                }
            }
            return RoadGraph(nodeLatLng, adjacency)
        }
    }

    /** أقرب عقدة حقيقية في الشبكة لنقطة معيّنة، مع المسافة إليها بالأمتار؛ null إن كانت الشبكة فارغة. */
    fun nearestNode(point: LatLng): Pair<Long, Double>? {
        var bestId: Long? = null
        var bestDist = Double.MAX_VALUE
        for ((id, latLng) in nodeLatLng) {
            val d = GeoMath.distanceMeters(point, latLng)
            if (d < bestDist) {
                bestDist = d
                bestId = id
            }
        }
        return bestId?.let { it to bestDist }
    }
}
