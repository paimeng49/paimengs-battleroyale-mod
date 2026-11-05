package com.paimeng.battleroyale.map;

/** 地图上的物资点标注。使用普通可变类，兼容 Minecraft 1.18.2 内置 Gson。 */
public class LootPoint {
    public double x;
    /** 标记方块上方的生成高度；旧 JSON / 旧 GUI 使用 -1，运行时自动回退到地面高度。 */
    public int y = -1;
    public double z;
    public String name;
    public int fontSize;

    /** Gson 反序列化使用的无参构造器。 */
    public LootPoint() {
        this(0.0, 0.0, "物资点", 12);
    }

    /** 代码中新建物资点使用的全参构造器。 */
    public LootPoint(double x, double z, String name, int fontSize) {
        this(x, -1, z, name, fontSize);
    }

    public LootPoint(double x, int y, double z, String name, int fontSize) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.name = name == null ? "物资点" : name;
        this.fontSize = Math.max(1, fontSize);
    }

    public int getY() {
        return y;
    }

    public void setY(int y) {
        this.y = y;
    }

    public int y() {
        return y;
    }

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getZ() {
        return z;
    }

    public void setZ(double z) {
        this.z = z;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "物资点" : name;
    }

    public int getFontSize() {
        return fontSize;
    }

    public void setFontSize(int fontSize) {
        this.fontSize = Math.max(1, fontSize);
    }

    /** 保留 record 风格访问器，兼容现有调用代码。 */
    public double x() {
        return x;
    }

    public double z() {
        return z;
    }

    public String name() {
        return name;
    }

    public int fontSize() {
        return fontSize;
    }
}
