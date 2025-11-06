package com.example.voxelrt.physics;

import com.bulletphysics.collision.broadphase.BroadphaseInterface;
import com.bulletphysics.collision.broadphase.DbvtBroadphase;
import com.bulletphysics.collision.dispatch.CollisionDispatcher;
import com.bulletphysics.collision.dispatch.DefaultCollisionConfiguration;
import com.bulletphysics.collision.dispatch.GhostPairCallback;
import com.bulletphysics.collision.dispatch.CollisionConfiguration;
import com.bulletphysics.collision.shapes.BoxShape;
import com.bulletphysics.collision.shapes.BvhTriangleMeshShape;
import com.bulletphysics.collision.shapes.CollisionShape;
import com.bulletphysics.collision.shapes.TriangleIndexVertexArray;
import com.bulletphysics.dynamics.DiscreteDynamicsWorld;
import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.dynamics.RigidBodyConstructionInfo;
import com.bulletphysics.dynamics.constraintsolver.SequentialImpulseConstraintSolver;
import com.bulletphysics.linearmath.DefaultMotionState;
import com.bulletphysics.linearmath.Transform;
import com.example.voxelrt.Blocks;
import com.example.voxelrt.Chunk;
import com.example.voxelrt.ChunkPos;
import com.example.voxelrt.mesh.MeshBuilder;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * Thin wrapper around the Bullet discrete dynamics world that translates chunk meshes into triangle
 * colliders and keeps them in sync with the voxel data.
 */
public final class PhysicsSystem implements AutoCloseable {
    private static final int FLOATS_PER_INSTANCE = 9;
    private static final float DEBRIS_SIZE = 0.35f;
    private static final float DEBRIS_MASS = 0.35f;
    private static final float DEBRIS_LIFETIME = 6.0f;
    private static final int MAX_DEBRIS = 128;

    private final CollisionConfiguration collisionConfig;
    private final CollisionDispatcher dispatcher;
    private final BroadphaseInterface broadphase;
    private final SequentialImpulseConstraintSolver solver;
    private final DiscreteDynamicsWorld world;
    private final Map<ChunkPos, StaticChunkBody> staticChunks = new HashMap<>();
    private final Set<ChunkPos> dirtyChunkColliders = new HashSet<>();
    private final ArrayDeque<VoxelDebris> debris = new ArrayDeque<>();
    private final CollisionShape debrisShape;
    private final Random random = new Random();

    public PhysicsSystem() {
        this(new Vector3f(0f, -9.81f, 0f));
    }

    public PhysicsSystem(Vector3f gravity) {
        this.collisionConfig = new DefaultCollisionConfiguration();
        this.dispatcher = new CollisionDispatcher(collisionConfig);
        this.broadphase = new DbvtBroadphase();
        this.solver = new SequentialImpulseConstraintSolver();
        this.world = new DiscreteDynamicsWorld(dispatcher, broadphase, solver, collisionConfig);
        this.world.setGravity(new Vector3f(gravity));
        this.world.getPairCache().setInternalGhostPairCallback(new GhostPairCallback());
        this.debrisShape = new BoxShape(new Vector3f(DEBRIS_SIZE * 0.5f, DEBRIS_SIZE * 0.5f, DEBRIS_SIZE * 0.5f));
    }

    public DiscreteDynamicsWorld world() {
        return world;
    }

    public void stepSimulation(float dtSeconds) {
        if (dtSeconds <= 0f) {
            return;
        }
        world.stepSimulation(dtSeconds, 4, dtSeconds / 4f);
        if (!debris.isEmpty()) {
            java.util.Iterator<VoxelDebris> it = debris.iterator();
            while (it.hasNext()) {
                VoxelDebris piece = it.next();
                piece.age += dtSeconds;
                if (piece.age >= DEBRIS_LIFETIME) {
                    world.removeRigidBody(piece.body);
                    it.remove();
                }
            }
        }
    }

    public void updateStaticChunkCollider(Chunk chunk, MeshBuilder.MeshData meshData) {
        Objects.requireNonNull(chunk, "chunk");
        ChunkPos pos = chunk.pos();
        if (pos == null) {
            return;
        }
        boolean missing = !staticChunks.containsKey(pos);
        boolean dirty = dirtyChunkColliders.remove(pos);
        if (!missing && !dirty && meshData != null && meshData.instanceCount() > 0) {
            return;
        }
        if (meshData == null || meshData.instanceCount() <= 0) {
            removeStaticChunk(pos);
            return;
        }

        StaticChunkBody existing = staticChunks.remove(pos);
        if (existing != null) {
            world.removeRigidBody(existing.body);
        }

        TriangleMeshBuffers buffers = buildTriangleMesh(meshData.instanceData(), meshData.instanceCount());
        if (buffers == null) {
            return;
        }

        CollisionShape shape = new BvhTriangleMeshShape(buffers.array, true);

        Transform transform = new Transform();
        transform.setIdentity();
        DefaultMotionState motionState = new DefaultMotionState(transform);
        RigidBodyConstructionInfo info = new RigidBodyConstructionInfo(0f, motionState, shape, new Vector3f());
        info.restitution = 0.1f;
        info.friction = 0.8f;
        RigidBody body = new RigidBody(info);
        world.addRigidBody(body);

        staticChunks.put(pos, new StaticChunkBody(body, buffers));
    }

    public void pruneStaticChunks(Collection<Chunk> loadedChunks) {
        if (staticChunks.isEmpty()) {
            return;
        }
        java.util.HashSet<ChunkPos> keep = new java.util.HashSet<>();
        for (Chunk chunk : loadedChunks) {
            if (chunk != null && chunk.pos() != null) {
                keep.add(chunk.pos());
            }
        }
        staticChunks.entrySet().removeIf(entry -> {
            if (keep.contains(entry.getKey())) {
                return false;
            }
            world.removeRigidBody(entry.getValue().body);
            return true;
        });
    }

    public void removeStaticChunk(ChunkPos pos) {
        StaticChunkBody removed = staticChunks.remove(pos);
        if (removed != null) {
            world.removeRigidBody(removed.body);
        }
        dirtyChunkColliders.remove(pos);
    }

    @Override
    public void close() {
        for (StaticChunkBody body : staticChunks.values()) {
            world.removeRigidBody(body.body);
        }
        staticChunks.clear();
        for (VoxelDebris piece : debris) {
            world.removeRigidBody(piece.body);
        }
        debris.clear();
    }

    public void onVoxelEdited(int wx, int wy, int wz, int previousBlock, int newBlock, org.joml.Vector3f impulse) {
        if (previousBlock == newBlock) {
            return;
        }
        ChunkPos pos = new ChunkPos(java.lang.Math.floorDiv(wx, Chunk.SX), java.lang.Math.floorDiv(wz, Chunk.SZ));
        markChunkDirty(pos);

        int localX = java.lang.Math.floorMod(wx, Chunk.SX);
        int localZ = java.lang.Math.floorMod(wz, Chunk.SZ);
        if (localX == 0) {
            markChunkDirty(new ChunkPos(pos.cx() - 1, pos.cz()));
        }
        if (localX == Chunk.SX - 1) {
            markChunkDirty(new ChunkPos(pos.cx() + 1, pos.cz()));
        }
        if (localZ == 0) {
            markChunkDirty(new ChunkPos(pos.cx(), pos.cz() - 1));
        }
        if (localZ == Chunk.SZ - 1) {
            markChunkDirty(new ChunkPos(pos.cx(), pos.cz() + 1));
        }

        if (previousBlock != Blocks.AIR && newBlock == Blocks.AIR) {
            spawnVoxelDebris(previousBlock, wx, wy, wz, impulse);
        }
    }

    public List<DebrisInstance> collectDebrisInstances() {
        if (debris.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        ArrayList<DebrisInstance> instances = new ArrayList<>(debris.size());
        Transform transform = new Transform();
        for (VoxelDebris piece : debris) {
            piece.body.getMotionState().getWorldTransform(transform);
            Matrix4f matrix = toMatrix(transform);
            float alpha = java.lang.Math.max(0f, 1f - (piece.age / DEBRIS_LIFETIME));
            instances.add(new DebrisInstance(matrix, piece.blockId, alpha));
        }
        return instances;
    }

    public void markChunkDirty(ChunkPos pos) {
        if (pos != null) {
            dirtyChunkColliders.add(pos);
        }
    }

    private TriangleMeshBuffers buildTriangleMesh(float[] instances, int instanceCount) {
        if (instances == null || instanceCount <= 0) {
            return null;
        }
        int vertexCount = instanceCount * 4;
        int triangleCount = instanceCount * 2;
        float[] vertices = new float[vertexCount * 3];
        int[] indices = new int[triangleCount * 3];
        int vertexCursor = 0;
        int indexCursor = 0;

        for (int i = 0; i < instanceCount; i++) {
            int base = i * FLOATS_PER_INSTANCE;
            float minX = instances[base];
            float minY = instances[base + 1];
            float minZ = instances[base + 2];
            float maxX = instances[base + 3];
            float maxY = instances[base + 4];
            float maxZ = instances[base + 5];
            int axis = Math.round(instances[base + 6]);
            boolean positive = instances[base + 7] >= 0.5f;

            int baseIndex = vertexCursor / 3;
            switch (axis) {
                case 0 -> vertexCursor = emitAxisX(vertices, vertexCursor, minX, minY, minZ, maxY, maxZ);
                case 1 -> vertexCursor = emitAxisY(vertices, vertexCursor, minX, minZ, maxX, maxZ, minY);
                case 2 -> vertexCursor = emitAxisZ(vertices, vertexCursor, minX, minY, maxX, maxY, minZ);
                default -> {
                    continue;
                }
            }
            if (positive) {
                indexCursor = emitPositive(indices, indexCursor, baseIndex);
            } else {
                indexCursor = emitNegative(indices, indexCursor, baseIndex);
            }
        }

        if (indexCursor == 0) {
            return null;
        }

        ByteBuffer vertexBuffer = ByteBuffer.allocateDirect(vertexCursor * Float.BYTES).order(ByteOrder.nativeOrder());
        FloatBuffer vertexFloats = vertexBuffer.asFloatBuffer();
        vertexFloats.put(vertices, 0, vertexCursor).flip();

        ByteBuffer indexBuffer = ByteBuffer.allocateDirect(indexCursor * Integer.BYTES).order(ByteOrder.nativeOrder());
        IntBuffer indexInts = indexBuffer.asIntBuffer();
        indexInts.put(indices, 0, indexCursor).flip();

        TriangleIndexVertexArray array = new TriangleIndexVertexArray();
        com.bulletphysics.collision.shapes.IndexedMesh mesh = new com.bulletphysics.collision.shapes.IndexedMesh();
        mesh.numTriangles = indexCursor / 3;
        mesh.numVertices = vertexCursor / 3;
        mesh.triangleIndexBase = indexBuffer;
        mesh.triangleIndexStride = 3 * Integer.BYTES;
        mesh.vertexBase = vertexBuffer;
        mesh.vertexStride = 3 * Float.BYTES;
        array.addIndexedMesh(mesh);

        TriangleMeshBuffers buffers = new TriangleMeshBuffers();
        buffers.vertexBuffer = vertexBuffer;
        buffers.indexBuffer = indexBuffer;
        buffers.mesh = mesh;
        buffers.array = array;
        return buffers;
    }

    private void spawnVoxelDebris(int blockId, int wx, int wy, int wz, org.joml.Vector3f impulse) {
        if (blockId == Blocks.AIR) {
            return;
        }
        Transform transform = new Transform();
        transform.setIdentity();
        float jitterX = (random.nextFloat() - 0.5f) * 0.1f;
        float jitterY = (random.nextFloat() - 0.5f) * 0.1f;
        float jitterZ = (random.nextFloat() - 0.5f) * 0.1f;
        transform.origin.set(wx + 0.5f + jitterX, wy + 0.5f + jitterY, wz + 0.5f + jitterZ);
        DefaultMotionState motionState = new DefaultMotionState(transform);
        Vector3f inertia = new Vector3f();
        debrisShape.calculateLocalInertia(DEBRIS_MASS, inertia);
        RigidBodyConstructionInfo info = new RigidBodyConstructionInfo(DEBRIS_MASS, motionState, debrisShape, inertia);
        info.restitution = 0.2f;
        info.friction = 0.7f;
        RigidBody body = new RigidBody(info);
        body.setDamping(0.05f, 0.25f);

        javax.vecmath.Vector3f impulseVec;
        if (impulse != null) {
            impulseVec = new javax.vecmath.Vector3f(impulse.x, impulse.y, impulse.z);
        } else {
            impulseVec = new javax.vecmath.Vector3f(
                    (random.nextFloat() - 0.5f) * 1.5f,
                    random.nextFloat() * 2.0f + 0.5f,
                    (random.nextFloat() - 0.5f) * 1.5f
            );
        }
        body.applyCentralImpulse(impulseVec);
        body.setAngularVelocity(new javax.vecmath.Vector3f(
                (random.nextFloat() - 0.5f) * 6.0f,
                (random.nextFloat() - 0.5f) * 6.0f,
                (random.nextFloat() - 0.5f) * 6.0f
        ));
        world.addRigidBody(body);

        VoxelDebris piece = new VoxelDebris(body, blockId);
        debris.addLast(piece);
        while (debris.size() > MAX_DEBRIS) {
            VoxelDebris removed = debris.removeFirst();
            world.removeRigidBody(removed.body);
        }
    }

    private Matrix4f toMatrix(Transform transform) {
        Quat4f rotation = new Quat4f();
        transform.getRotation(rotation);
        Quaternionf q = new Quaternionf(rotation.x, rotation.y, rotation.z, rotation.w);
        return new Matrix4f()
                .translation(transform.origin.x, transform.origin.y, transform.origin.z)
                .rotate(q)
                .scale(DEBRIS_SIZE);
    }

    private static int emitAxisX(float[] vertices, int cursor, float x, float minY, float minZ, float maxY, float maxZ) {
        float y0 = minY;
        float y1 = maxY;
        float z0 = minZ;
        float z1 = maxZ;
        cursor = putVertex(vertices, cursor, x, y0, z0);
        cursor = putVertex(vertices, cursor, x, y0, z1);
        cursor = putVertex(vertices, cursor, x, y1, z1);
        return putVertex(vertices, cursor, x, y1, z0);
    }

    private static int emitAxisY(float[] vertices, int cursor, float minX, float minZ, float maxX, float maxZ, float y) {
        cursor = putVertex(vertices, cursor, minX, y, minZ);
        cursor = putVertex(vertices, cursor, maxX, y, minZ);
        cursor = putVertex(vertices, cursor, maxX, y, maxZ);
        return putVertex(vertices, cursor, minX, y, maxZ);
    }

    private static int emitAxisZ(float[] vertices, int cursor, float minX, float minY, float maxX, float maxY, float z) {
        cursor = putVertex(vertices, cursor, minX, minY, z);
        cursor = putVertex(vertices, cursor, maxX, minY, z);
        cursor = putVertex(vertices, cursor, maxX, maxY, z);
        return putVertex(vertices, cursor, minX, maxY, z);
    }

    private static int putVertex(float[] vertices, int cursor, float x, float y, float z) {
        vertices[cursor++] = x;
        vertices[cursor++] = y;
        vertices[cursor++] = z;
        return cursor;
    }

    private static int emitPositive(int[] indices, int cursor, int base) {
        indices[cursor++] = base;
        indices[cursor++] = base + 1;
        indices[cursor++] = base + 2;
        indices[cursor++] = base;
        indices[cursor++] = base + 2;
        indices[cursor++] = base + 3;
        return cursor;
    }

    private static int emitNegative(int[] indices, int cursor, int base) {
        indices[cursor++] = base;
        indices[cursor++] = base + 2;
        indices[cursor++] = base + 1;
        indices[cursor++] = base;
        indices[cursor++] = base + 3;
        indices[cursor++] = base + 2;
        return cursor;
    }

    private static final class StaticChunkBody {
        final RigidBody body;
        final TriangleMeshBuffers buffers;

        StaticChunkBody(RigidBody body, TriangleMeshBuffers buffers) {
            this.body = body;
            this.buffers = buffers;
        }
    }

    private static final class VoxelDebris {
        final RigidBody body;
        final int blockId;
        float age = 0f;

        VoxelDebris(RigidBody body, int blockId) {
            this.body = body;
            this.blockId = blockId;
        }
    }

    public record DebrisInstance(Matrix4f transform, int blockId, float alpha) {
    }

    private static final class TriangleMeshBuffers {
        ByteBuffer vertexBuffer;
        ByteBuffer indexBuffer;
        com.bulletphysics.collision.shapes.IndexedMesh mesh;
        TriangleIndexVertexArray array;
    }
}
