package com.example.voxelrt;
import org.joml.Vector3f;
public class Raycast{
    public static class Hit{ public int x,y,z; public int nx,ny,nz; }
    public static Hit raycast(ChunkManager cm, Vector3f origin, Vector3f dir, float maxDist){
        Vector3f rd=new Vector3f(dir).normalize();
        int x=(int)java.lang.Math.floor(origin.x), y=(int)java.lang.Math.floor(origin.y), z=(int)java.lang.Math.floor(origin.z);
        int sx=rd.x>0?1:-1, sy=rd.y>0?1:-1, sz=rd.z>0?1:-1;
        float tMaxX=intBound(origin.x,rd.x), tMaxY=intBound(origin.y,rd.y), tMaxZ=intBound(origin.z,rd.z);
        float tDeltaX=sx/rd.x, tDeltaY=sy/rd.y, tDeltaZ=sz/rd.z;
        int nx=0,ny=0,nz=0; float t=0f;
        while(t<=maxDist){
            if (y>=0 && y<Chunk.SY && cm.sample(x,y,z)!=Blocks.AIR){ Hit h=new Hit(); h.x=x;h.y=y;h.z=z; h.nx=nx;h.ny=ny;h.nz=nz; return h; }
            if (tMaxX < tMaxY){ if (tMaxX < tMaxZ){ x+=sx; t=tMaxX; tMaxX+=tDeltaX; nx=-sx;ny=0;nz=0; }
                                 else { z+=sz; t=tMaxZ; tMaxZ+=tDeltaZ; nx=0;ny=0;nz=-sz; } }
            else { if (tMaxY < tMaxZ){ y+=sy; t=tMaxY; tMaxY+=tDeltaY; nx=0;ny=-sy;nz=0; }
                   else { z+=sz; t=tMaxZ; tMaxZ+=tDeltaZ; nx=0;ny=0;nz=-sz; } }
        }
        return null;
    }
    private static float intBound(float s,float ds){ return ds>0? ((float)java.lang.Math.floor(s+1)-s)/ds : (s-(float)java.lang.Math.floor(s))/(-ds); }
}