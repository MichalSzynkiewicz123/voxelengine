package com.example.voxelrt;
public class WorldGenerator{
    private final Noise height=new Noise(1337L);
    private final Noise temp=new Noise(1337L*17L+5L);
    private final int seaLevel;
    public WorldGenerator(long seed,int seaLevel){ this.seaLevel=seaLevel; }
    public int sampleBlock(int x,int y,int z){
        double h = 28 + 24*height.fractal2D(x*0.012, z*0.012, 5);
        int ground = (int)java.lang.Math.round(h) + 64;
        double t = temp.fractal2D(x*0.004, z*0.004, 4);
        if (y>ground) return Blocks.AIR;
        if (ground>=95 && t<-0.15){
            if (y==ground) return Blocks.SNOW;
            if (y>=ground-3) return Blocks.DIRT;
            return Blocks.STONE;
        } else if (ground<=seaLevel+2 && t>0.2){
            if (y==ground) return Blocks.SAND;
            if (y>=ground-3) return Blocks.SAND;
            return Blocks.DIRT;
        } else {
            if (y==ground) return Blocks.GRASS;
            if (y>=ground-3) return Blocks.DIRT;
            return Blocks.STONE;
        }
    }
}