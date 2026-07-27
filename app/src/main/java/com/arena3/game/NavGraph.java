package com.arena3.game;

import com.arena3.core.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Navigation mesh for the bots, derived from the level geometry instead of being
 * hand-authored: the arena is sampled on a grid, every standable spot becomes a
 * node, and nodes are linked when a player-sized box can actually get from one
 * to the other. Jump pads and teleporters contribute one-way links.
 *
 * <p>Paths come out of a small A* over the resulting graph.
 */
public final class NavGraph {

    /** Ordinary walking link. */
    public static final int LINK_WALK = 0;
    /** Requires a jump to clear a step or a gap. */
    public static final int LINK_JUMP = 1;
    /** A drop the bot can simply walk off. */
    public static final int LINK_FALL = 2;
    /** Step onto a jump pad and get thrown. */
    public static final int LINK_PAD = 3;
    /** Walk into a teleporter. */
    public static final int LINK_TELEPORT = 4;

    private static final float SAMPLE_STEP = 96f;
    private static final float MAX_LINK_DIST = 260f;
    private static final float STEP_HEIGHT = PlayerMove.STEP_SIZE;
    /** A standing jump clears 45 units, plus the 18-unit step-up on landing. */
    private static final float MAX_JUMP_UP = 58f;
    /** How far the ground may stray from the straight line and still count as one continuous surface. */
    private static final float SURFACE_TOLERANCE = 40f;
    /** Drops beyond this are treated as one-way suicide, not a route. */
    private static final float MAX_FALL = 640f;
    /** A running jump clears roughly 210 units; stay well inside that. */
    private static final float MAX_GAP_JUMP = 132f;

    public int nodeCount;
    public float[] nx = new float[0];
    public float[] ny = new float[0];
    public float[] nz = new float[0];

    /** CSR adjacency. */
    public int[] linkStart = new int[0];
    public int[] linkEnd = new int[0];
    public int[] linkTarget = new int[0];
    public float[] linkCost = new float[0];
    public int[] linkType = new int[0];

    // spatial hash for nearest-node lookups
    private static final float CELL = 192f;
    private float originX, originY;
    private int gridW, gridH;
    private int[][] cellNodes;

    // A* working set
    private float[] gScore;
    private float[] fScore;
    private int[] cameFrom;
    private int[] heap;
    private int[] heapIndex;
    private int heapSize;
    private int[] closedStamp;
    private int[] openStamp;
    private int searchStamp;

    private final Trace trace = new Trace();
    private final Vec3 a = new Vec3();
    private final Vec3 b = new Vec3();
    private final Vec3 mins = new Vec3(-15, -15, -24);
    private final Vec3 maxs = new Vec3(15, 15, 32);
    private final Vec3 thin = new Vec3(-12, -12, -24);
    private final Vec3 thinMax = new Vec3(12, 12, 24);

    /** Builds the graph. Cheap enough to run on a loading thread. */
    public void build(MapDef map, CollisionWorld world) {
        List<float[]> nodes = sampleStandablePositions(world);
        applyEntityNodes(map, world, nodes);
        mergeClose(nodes, 56f);

        nodeCount = nodes.size();
        nx = new float[nodeCount];
        ny = new float[nodeCount];
        nz = new float[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            nx[i] = nodes.get(i)[0];
            ny[i] = nodes.get(i)[1];
            nz[i] = nodes.get(i)[2];
        }
        buildSpatialIndex();
        buildLinks(world);
        addPadAndTeleportLinks(map);

        gScore = new float[nodeCount];
        fScore = new float[nodeCount];
        cameFrom = new int[nodeCount];
        heap = new int[nodeCount + 2];
        heapIndex = new int[nodeCount];
        closedStamp = new int[nodeCount];
        openStamp = new int[nodeCount];
    }

    // ------------------------------------------------------------- generation

    private List<float[]> sampleStandablePositions(CollisionWorld world) {
        List<float[]> out = new ArrayList<>();
        Vec3 wmin = world.worldMins(), wmax = world.worldMaxs();
        for (float x = wmin.x + SAMPLE_STEP * 0.5f; x < wmax.x; x += SAMPLE_STEP) {
            for (float y = wmin.y + SAMPLE_STEP * 0.5f; y < wmax.y; y += SAMPLE_STEP) {
                float z = wmax.z - 8f;
                int guard = 0;
                while (z > wmin.z && guard++ < 24) {
                    a.set(x, y, z);
                    b.set(x, y, wmin.z - 64f);
                    world.traceBox(trace, a, b, mins, maxs, Contents.MASK_PLAYER);
                    if (trace.startSolid) {
                        z -= 48f;
                        continue;
                    }
                    if (trace.fraction >= 1f) break;          // fell out of the level
                    float floorZ = trace.endPos.z;
                    if (trace.normal.z >= PlayerMove.MIN_WALK_NORMAL && !hazardous(world, x, y, floorZ)) {
                        out.add(new float[]{x, y, floorZ});
                    }
                    z = floorZ - 80f;                          // look for a deck below
                }
            }
        }
        return out;
    }

    private final Vec3 hazMins = new Vec3();
    private final Vec3 hazMaxs = new Vec3();

    /** True when a player standing here would have their feet in something nasty. */
    private boolean hazardous(CollisionWorld world, float x, float y, float z) {
        hazMins.set(x - 15f, y - 15f, z - 24f);
        hazMaxs.set(x + 15f, y + 15f, z - 4f);
        return (world.boxContents(hazMins, hazMaxs) & Contents.LAVA) != 0;
    }

    /** Items, spawns and pad targets are places bots must be able to reach. */
    private void applyEntityNodes(MapDef map, CollisionWorld world, List<float[]> nodes) {
        for (MapDef.ItemSpawn it : map.items) addDropped(world, nodes, it.pos.x, it.pos.y, it.pos.z + 32f);
        for (MapDef.Spawn sp : map.spawns) addDropped(world, nodes, sp.pos.x, sp.pos.y, sp.pos.z + 32f);
        for (MapDef.JumpPad pad : map.jumpPads) {
            addDropped(world, nodes, (pad.mins.x + pad.maxs.x) * 0.5f, (pad.mins.y + pad.maxs.y) * 0.5f,
                    pad.mins.z + 40f);
            addDropped(world, nodes, pad.target.x, pad.target.y, pad.target.z + 48f);
        }
        for (MapDef.Teleporter t : map.teleporters) {
            addDropped(world, nodes, (t.mins.x + t.maxs.x) * 0.5f, (t.mins.y + t.maxs.y) * 0.5f, t.mins.z + 40f);
            addDropped(world, nodes, t.dest.x, t.dest.y, t.dest.z + 40f);
        }
    }

    private void addDropped(CollisionWorld world, List<float[]> nodes, float x, float y, float z) {
        a.set(x, y, z);
        b.set(x, y, z - 512f);
        world.traceBox(trace, a, b, mins, maxs, Contents.MASK_PLAYER);
        if (trace.startSolid || trace.fraction >= 1f) return;
        if (trace.normal.z < PlayerMove.MIN_WALK_NORMAL) return;
        nodes.add(new float[]{x, y, trace.endPos.z});
    }

    private void mergeClose(List<float[]> nodes, float radius) {
        float r2 = radius * radius;
        for (int i = 0; i < nodes.size(); i++) {
            float[] p = nodes.get(i);
            for (int j = nodes.size() - 1; j > i; j--) {
                float[] q = nodes.get(j);
                float dx = p[0] - q[0], dy = p[1] - q[1], dz = p[2] - q[2];
                if (dx * dx + dy * dy < r2 && Math.abs(dz) < 40f) {
                    nodes.remove(j);
                }
            }
        }
    }

    private void buildSpatialIndex() {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < nodeCount; i++) {
            minX = Math.min(minX, nx[i]);
            maxX = Math.max(maxX, nx[i]);
            minY = Math.min(minY, ny[i]);
            maxY = Math.max(maxY, ny[i]);
        }
        if (nodeCount == 0) {
            minX = minY = 0;
            maxX = maxY = 1;
        }
        originX = minX - CELL;
        originY = minY - CELL;
        gridW = Math.max(1, (int) ((maxX - originX) / CELL) + 2);
        gridH = Math.max(1, (int) ((maxY - originY) / CELL) + 2);
        int[] counts = new int[gridW * gridH];
        for (int i = 0; i < nodeCount; i++) counts[cellOf(nx[i], ny[i])]++;
        cellNodes = new int[gridW * gridH][];
        for (int i = 0; i < cellNodes.length; i++) cellNodes[i] = new int[counts[i]];
        int[] fill = new int[gridW * gridH];
        for (int i = 0; i < nodeCount; i++) {
            int c = cellOf(nx[i], ny[i]);
            cellNodes[c][fill[c]++] = i;
        }
    }

    private int cellOf(float x, float y) {
        int cx = (int) ((x - originX) / CELL);
        int cy = (int) ((y - originY) / CELL);
        cx = Math.max(0, Math.min(gridW - 1, cx));
        cy = Math.max(0, Math.min(gridH - 1, cy));
        return cy * gridW + cx;
    }

    private void buildLinks(CollisionWorld world) {
        List<int[]> links = new ArrayList<>();          // {from, to, type}
        List<Float> costs = new ArrayList<>();
        int[] starts = new int[nodeCount + 1];

        for (int i = 0; i < nodeCount; i++) {
            final int from = i;
            starts[i] = links.size();
            forEachNear(nx[i], ny[i], MAX_LINK_DIST, j -> {
                if (j == from) return;
                float dx = nx[j] - nx[from], dy = ny[j] - ny[from], dz = nz[j] - nz[from];
                float flat = (float) Math.sqrt(dx * dx + dy * dy);
                if (flat > MAX_LINK_DIST) return;
                int type = classify(world, from, j, flat, dz);
                if (type < 0) return;
                float cost = (float) Math.sqrt(flat * flat + dz * dz);
                if (type == LINK_JUMP) cost += 60f;
                if (type == LINK_FALL) cost += 20f;
                links.add(new int[]{from, j, type});
                costs.add(cost);
            });
        }
        starts[nodeCount] = links.size();

        linkStart = new int[nodeCount];
        linkEnd = new int[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            linkStart[i] = starts[i];
            linkEnd[i] = starts[i + 1];
        }
        linkTarget = new int[links.size()];
        linkCost = new float[links.size()];
        linkType = new int[links.size()];
        for (int i = 0; i < links.size(); i++) {
            linkTarget[i] = links.get(i)[1];
            linkType[i] = links.get(i)[2];
            linkCost[i] = costs.get(i);
        }
    }

    /** Returns a link type, or -1 when the two nodes are not connected. */
    private int classify(CollisionWorld world, int i, int j, float flat, float dz) {
        // A climb only survives if it turns out to be a continuous ramp or
        // staircase, which the sampling below determines; anything beyond a
        // storey up is hopeless either way.
        if (dz > 260f) return -1;
        if (dz < -MAX_FALL) return -1;

        // The walk path is traced at the height of the higher end plus a step,
        // so ledges and stair noses do not block otherwise-valid links.
        float baseZ = Math.max(nz[i], nz[j]) + STEP_HEIGHT;
        a.set(nx[i], ny[i], baseZ);
        b.set(nx[j], ny[j], baseZ);
        world.traceBox(trace, a, b, thin, thinMax, Contents.MASK_PLAYER);
        if (trace.fraction < 0.999f || trace.startSolid) return -1;

        // Walk the segment looking for holes in the floor, and check whether the
        // ground follows the straight line between the two nodes — that is what
        // separates a ramp or a staircase (walkable at any gradient) from a
        // ledge of the same height difference (which needs a jump).
        boolean gap = false;
        boolean continuous = true;
        int samples = Math.max(2, (int) (flat / 48f));
        for (int s = 1; s < samples; s++) {
            float t = s / (float) samples;
            float sx = nx[i] + (nx[j] - nx[i]) * t;
            float sy = ny[i] + (ny[j] - ny[i]) * t;
            float sz = nz[i] + (nz[j] - nz[i]) * t;
            a.set(sx, sy, sz + 48f);
            b.set(sx, sy, sz - 120f);
            world.traceBox(trace, a, b, thin, thinMax, Contents.MASK_PLAYER);
            if (trace.fraction >= 1f || trace.startSolid || hazardous(world, sx, sy, trace.endPos.z)) {
                gap = true;
                continuous = false;
                break;
            }
            if (Math.abs(trace.endPos.z - sz) > SURFACE_TOLERANCE) continuous = false;
        }

        if (gap) {
            return flat <= MAX_GAP_JUMP ? LINK_JUMP : -1;
        }
        if (dz > STEP_HEIGHT) {
            // A ramp or a flight of stairs is walkable at any height, as long as
            // it is not steeper than a player can actually climb.
            if (continuous && dz <= flat * 0.95f) return LINK_WALK;
            return dz <= MAX_JUMP_UP ? LINK_JUMP : -1;
        }
        if (dz < -48f) return continuous ? LINK_WALK : LINK_FALL;
        return LINK_WALK;
    }

    private void addPadAndTeleportLinks(MapDef map) {
        List<int[]> extra = new ArrayList<>();
        for (MapDef.JumpPad pad : map.jumpPads) {
            int from = nearest((pad.mins.x + pad.maxs.x) * 0.5f, (pad.mins.y + pad.maxs.y) * 0.5f, pad.mins.z + 8f);
            int to = nearest(pad.target.x, pad.target.y, pad.target.z);
            if (from >= 0 && to >= 0 && from != to) extra.add(new int[]{from, to, LINK_PAD});
        }
        for (MapDef.Teleporter t : map.teleporters) {
            int from = nearest((t.mins.x + t.maxs.x) * 0.5f, (t.mins.y + t.maxs.y) * 0.5f, t.mins.z + 8f);
            int to = nearest(t.dest.x, t.dest.y, t.dest.z);
            if (from >= 0 && to >= 0 && from != to) extra.add(new int[]{from, to, LINK_TELEPORT});
        }
        if (extra.isEmpty()) return;

        // Rebuild the CSR arrays with the extra links spliced in.
        int total = linkTarget.length + extra.size();
        int[] newTarget = new int[total];
        float[] newCost = new float[total];
        int[] newType = new int[total];
        int[] newStart = new int[nodeCount];
        int[] newEnd = new int[nodeCount];
        int w = 0;
        for (int i = 0; i < nodeCount; i++) {
            newStart[i] = w;
            for (int k = linkStart[i]; k < linkEnd[i]; k++) {
                newTarget[w] = linkTarget[k];
                newCost[w] = linkCost[k];
                newType[w] = linkType[k];
                w++;
            }
            for (int[] e : extra) {
                if (e[0] != i) continue;
                newTarget[w] = e[1];
                // Pads and teleports are cheap: they cover ground very fast.
                newCost[w] = 40f;
                newType[w] = e[2];
                w++;
            }
            newEnd[i] = w;
        }
        linkTarget = Arrays.copyOf(newTarget, w);
        linkCost = Arrays.copyOf(newCost, w);
        linkType = Arrays.copyOf(newType, w);
        linkStart = newStart;
        linkEnd = newEnd;
    }

    private interface NodeVisitor {
        void visit(int node);
    }

    private void forEachNear(float x, float y, float radius, NodeVisitor v) {
        int r = (int) Math.ceil(radius / CELL);
        int cx = (int) ((x - originX) / CELL);
        int cy = (int) ((y - originY) / CELL);
        for (int gy = cy - r; gy <= cy + r; gy++) {
            if (gy < 0 || gy >= gridH) continue;
            for (int gx = cx - r; gx <= cx + r; gx++) {
                if (gx < 0 || gx >= gridW) continue;
                for (int n : cellNodes[gy * gridW + gx]) v.visit(n);
            }
        }
    }

    // ---------------------------------------------------------------- queries

    /** Closest node to a world position, or -1 when the graph is empty. */
    public int nearest(float x, float y, float z) {
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (float radius = CELL; radius <= CELL * 6 && best < 0; radius *= 2f) {
            int r = (int) Math.ceil(radius / CELL);
            int cx = (int) ((x - originX) / CELL);
            int cy = (int) ((y - originY) / CELL);
            for (int gy = cy - r; gy <= cy + r; gy++) {
                if (gy < 0 || gy >= gridH) continue;
                for (int gx = cx - r; gx <= cx + r; gx++) {
                    if (gx < 0 || gx >= gridW) continue;
                    for (int n : cellNodes[gy * gridW + gx]) {
                        float dx = nx[n] - x, dy = ny[n] - y, dz = (nz[n] - z) * 1.6f;
                        float d = dx * dx + dy * dy + dz * dz;
                        if (d < bestD) {
                            bestD = d;
                            best = n;
                        }
                    }
                }
            }
        }
        return best;
    }

    public int nearest(Vec3 p) {
        return nearest(p.x, p.y, p.z);
    }

    public void nodePos(int node, Vec3 out) {
        out.set(nx[node], ny[node], nz[node]);
    }

    /** Link type from {@code from} to {@code to}, or -1 if they are not linked. */
    public int linkTypeBetween(int from, int to) {
        for (int k = linkStart[from]; k < linkEnd[from]; k++) {
            if (linkTarget[k] == to) return linkType[k];
        }
        return -1;
    }

    /**
     * A* from {@code start} to {@code goal}. Writes the node sequence into
     * {@code outPath} (start first) and returns its length, or 0 if unreachable.
     */
    public int findPath(int start, int goal, int[] outPath) {
        if (nodeCount == 0 || start < 0 || goal < 0) return 0;
        if (start == goal) {
            outPath[0] = start;
            return 1;
        }
        searchStamp++;
        heapSize = 0;
        Arrays.fill(gScore, Float.MAX_VALUE);
        gScore[start] = 0f;
        fScore[start] = heuristic(start, goal);
        cameFrom[start] = -1;
        heapPush(start);

        while (heapSize > 0) {
            int current = heapPop();
            if (current == goal) return reconstruct(current, outPath);
            closedStamp[current] = searchStamp;

            for (int k = linkStart[current]; k < linkEnd[current]; k++) {
                int next = linkTarget[k];
                if (closedStamp[next] == searchStamp) continue;
                float tentative = gScore[current] + linkCost[k];
                if (tentative >= gScore[next]) continue;
                cameFrom[next] = current;
                gScore[next] = tentative;
                fScore[next] = tentative + heuristic(next, goal);
                heapPush(next);
            }
        }
        return 0;
    }

    private int reconstruct(int node, int[] outPath) {
        int n = 0;
        int cursor = node;
        while (cursor >= 0 && n < outPath.length) {
            outPath[n++] = cursor;
            cursor = cameFrom[cursor];
        }
        // Reverse into start-first order.
        for (int i = 0, j = n - 1; i < j; i++, j--) {
            int t = outPath[i];
            outPath[i] = outPath[j];
            outPath[j] = t;
        }
        return n;
    }

    private float heuristic(int from, int to) {
        float dx = nx[from] - nx[to], dy = ny[from] - ny[to], dz = nz[from] - nz[to];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ---- binary heap keyed on fScore, with decrease-key ----

    /** Inserts the node, or re-sifts it if a cheaper route to it was just found. */
    private void heapPush(int node) {
        if (openStamp[node] == searchStamp) {
            siftUp(heapIndex[node]);
            return;
        }
        openStamp[node] = searchStamp;
        int i = ++heapSize;
        heap[i] = node;
        heapIndex[node] = i;
        siftUp(i);
    }

    private int heapPop() {
        int top = heap[1];
        openStamp[top] = 0;
        heap[1] = heap[heapSize--];
        if (heapSize > 0) {
            heapIndex[heap[1]] = 1;
            siftDown(1);
        }
        return top;
    }

    private void siftUp(int i) {
        while (i > 1) {
            int parent = i >> 1;
            if (fScore[heap[parent]] <= fScore[heap[i]]) break;
            swap(parent, i);
            i = parent;
        }
    }

    private void siftDown(int i) {
        while (true) {
            int l = i << 1, r = l + 1, best = i;
            if (l <= heapSize && fScore[heap[l]] < fScore[heap[best]]) best = l;
            if (r <= heapSize && fScore[heap[r]] < fScore[heap[best]]) best = r;
            if (best == i) return;
            swap(best, i);
            i = best;
        }
    }

    private void swap(int i, int j) {
        int t = heap[i];
        heap[i] = heap[j];
        heap[j] = t;
        heapIndex[heap[i]] = i;
        heapIndex[heap[j]] = j;
    }
}
