package com.example.voxelrt;
import org.joml.*;
public class Camera{
    public Vector3f position;
    private float yawDeg=-90f, pitchDeg=0f;
    public Camera(Vector3f start){ position = new Vector3f(start); }
    public void addYawPitch(float dx,float dy){ yawDeg+=dx*0.15f; pitchDeg-=dy*0.15f; if(pitchDeg>89) pitchDeg=89; if(pitchDeg<-89) pitchDeg=-89; }
    public Matrix4f viewMatrix(){ Vector3f f=getForward(); Vector3f t=new Vector3f(position).add(f); return new Matrix4f().lookAt(position,t,new Vector3f(0,1,0)); }
    public Vector3f getForward(){
        float yaw=(float)java.lang.Math.toRadians(yawDeg), pit=(float)java.lang.Math.toRadians(pitchDeg);
        float x=(float)(java.lang.Math.cos(yaw)*java.lang.Math.cos(pit));
        float y=(float)(java.lang.Math.sin(pit));
        float z=(float)(java.lang.Math.sin(yaw)*java.lang.Math.cos(pit));
        return new Vector3f(x,y,z).normalize();
    }
}