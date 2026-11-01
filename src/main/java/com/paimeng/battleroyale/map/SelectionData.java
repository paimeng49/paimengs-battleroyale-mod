package com.paimeng.battleroyale.map;

/** 管理员在副本世界里圈定的两点选区。 */
public final class SelectionData {
    public Integer x1;
    public Integer z1;
    public Integer x2;
    public Integer z2;
    public String archiveName = "";
    public String archivePath = "";

    public boolean hasPoint1() {
        return x1 != null && z1 != null;
    }

    public boolean hasPoint2() {
        return x2 != null && z2 != null;
    }

    public boolean isComplete() {
        return hasPoint1() && hasPoint2();
    }

    public int minX() {
        return Math.min(x1, x2);
    }

    public int maxX() {
        return Math.max(x1, x2);
    }

    public int minZ() {
        return Math.min(z1, z2);
    }

    public int maxZ() {
        return Math.max(z1, z2);
    }

    public int spanX() {
        return maxX() - minX();
    }

    public int spanZ() {
        return maxZ() - minZ();
    }
}
