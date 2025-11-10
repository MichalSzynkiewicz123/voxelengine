// ---------------------------------------------------------------------------------------------
// Implementation of the Preetham daylight sky model used for environment lighting.
// Generates Perez coefficients and converts chromaticity back into linear sRGB values.
// ---------------------------------------------------------------------------------------------
// -------- Preetham daylight (no vec5!) --------
// Perez coefficients (A..E) via out params
void coeffsY(float T, out float A, out float B, out float C, out float D, out float E){
    A =  0.17872*T - 1.46303;
    B = -0.35540*T + 0.42749;
    C = -0.02266*T + 5.32505;
    D =  0.12064*T - 2.57705;
    E = -0.06696*T + 0.37027;
}
void coeffsX(float T, out float A, out float B, out float C, out float D, out float E){
    A = -0.01925*T - 0.25922;
    B = -0.06651*T + 0.00081;
    C = -0.00041*T + 0.21247;
    D = -0.06409*T - 0.89887;
    E = -0.00325*T + 0.04517;
}
void coeffsYc(float T, out float A, out float B, out float C, out float D, out float E){
    A = -0.01669*T - 0.26078;
    B = -0.09495*T + 0.00921;
    C = -0.00792*T + 0.21023;
    D = -0.04405*T - 1.65369;
    E = -0.01092*T + 0.05291;
}

float zenithY(float T, float thetaS){
    float chi = (4.0/9.0 - T/120.0) * (PI - 2.0*thetaS);
    return ((4.0453*T - 4.9710) * tan(chi) - 0.2155*T + 2.4192) * 1000.0;// cd/m^2
}
float zenithx(float T, float thetaS){
    float t = thetaS;
    return (0.00165*t*t*t - 0.00374*t*t + 0.00208*t) * T*T
    + (-0.02902*t*t*t + 0.06377*t*t - 0.03202*t + 0.00394) * T
    + (0.11693*t*t*t - 0.21196*t*t + 0.06052*t + 0.25885);
}
float zenithy(float T, float thetaS){
    float t = thetaS;
    return (0.00275*t*t*t - 0.00610*t*t + 0.00317*t) * T*T
    + (-0.04214*t*t*t + 0.08970*t*t - 0.04153*t + 0.00516) * T
    + (0.15346*t*t*t - 0.26756*t*t + 0.06669*t + 0.26688);
}

vec3 preethamSkyRGB(vec3 dir, vec3 sunDir, float T){
    dir = normalize(dir); sunDir = normalize(sunDir);
    float cosTheta = clamp(dir.y, 0.0, 1.0);
    float theta    = acos(cosTheta);
    float cosThetaS= clamp(sunDir.y, 0.0, 1.0);
    float thetaS   = acos(cosThetaS);
    float gamma    = acos(clamp(dot(dir, sunDir), -1.0, 1.0));

    // Coeffs
    float Ax, Bx, Cx, Dx, Ex; coeffsX(T, Ax, Bx, Cx, Dx, Ex);
    float Ay, By, Cy, Dy, Ey; coeffsYc(T, Ay, By, Cy, Dy, Ey);
    float AY, BY, CY, DY, EY; coeffsY(T, AY, BY, CY, DY, EY);

    // Perez terms (normalized with zenith configuration)
    float denomX = (1.0 + Ax*exp(Bx) + Cx*exp(Dx*thetaS) + Ex*cos(thetaS)*cos(thetaS));
    float denomYc= (1.0 + Ay*exp(By) + Cy*exp(Dy*thetaS) + Ey*cos(thetaS)*cos(thetaS));
    float denomY = (1.0 + AY*exp(BY) + CY*exp(DY*thetaS) + EY*cos(thetaS)*cos(thetaS));

    float numX = (1.0 + Ax*exp(Bx/(cosTheta+1e-5)) + Cx*exp(Dx*gamma) + Ex*cos(gamma)*cos(gamma));
    float numYc= (1.0 + Ay*exp(By/(cosTheta+1e-5)) + Cy*exp(Dy*gamma) + Ey*cos(gamma)*cos(gamma));
    float numY = (1.0 + AY*exp(BY/(cosTheta+1e-5)) + CY*exp(DY*gamma) + EY*cos(gamma)*cos(gamma));

    float Px = numX / denomX;
    float Py = numYc/ denomYc;
    float PY = numY / denomY;

    float Yz = zenithY(T, thetaS);
    float xz = zenithx(T, thetaS);
    float yz = zenithy(T, thetaS);

    float x = saturate(Px * xz);
    float y = saturate(Py * yz);
    float Y = max(0.0, PY * Yz);// luminance

    // xyY -> XYZ
    float X = (y > 1e-5) ? (Y * x / y) : 0.0;
    float Z = (y > 1e-5) ? (Y * (1.0 - x - y) / y) : 0.0;

    // XYZ -> linear sRGB
    vec3 rgb = mat3(3.2406, -1.5372, -0.4986,
    -0.9689, 1.8758, 0.0415,
    0.0557, -0.2040, 1.0570) * vec3(X, Y, Z);

    // Bring into [0,1]-ish range (empirical scale; adjust with uSkyIntensity)
    rgb *= 1.0/60000.0;
    return max(rgb, vec3(0.0));
}

vec3 envSky(vec3 dir){
    return (uSkyModel==1)
    ? preethamSkyRGB(dir, uSunDir, uTurbidity) * uSkyIntensity
    : skyGradient(dir) * uSkyIntensity;
}

