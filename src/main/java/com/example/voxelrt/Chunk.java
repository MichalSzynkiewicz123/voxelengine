package com.example.voxelrt;
public class Chunk{
    public static final int SX=16, SY=256, SZ=16;
    public final ChunkPos pos;
    private final int[] vox=new int[SX*SY*SZ];
    public Chunk(ChunkPos p){ this.pos=p; }
    private static int idx(int x,int y,int z){ return x + y*SX + z*SX*SY; }
    public int get(int x,int y,int z){ if((x|y|z)<0||x>=SX||y>=SY||z>=SZ) return Blocks.AIR; return vox[idx(x,y,z)]; }
    public void set(int x,int y,int z,int b){ if((x|y|z)<0||x>=SX||y>=SY||z>=SZ) return; vox[idx(x,y,z)]=b; }
    public void fill(WorldGenerator gen){
        int wx0=pos.cx()*SX, wz0=pos.cz()*SZ;
        for (int z=0; z<SZ; z++) for(int x=0; x<SX; x++){
            int wx=wx0+x, wz=wz0+z;
            for(int y=0; y<SY; y++) vox[idx(x,y,z)] = gen.sampleBlock(wx,y,wz);
        }
    }
}