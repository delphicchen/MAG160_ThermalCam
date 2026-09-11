package cn.com.magnity.coresdk.types;

/* loaded from: classes.dex */
public class JRect {
    private int x1;
    private int x2;
    private int y1;
    private int y2;

    JRect() {
        this.x1 = 0;
        this.y1 = 0;
        this.x2 = -1;
        this.y2 = -1;
    }

    JRect(int x, int y, int w, int h) {
        this.x1 = x;
        this.y1 = y;
        this.x2 = (x + w) - 1;
        this.y2 = (y + h) - 1;
    }

    public int left() {
        return this.x1;
    }

    public int top() {
        return this.y1;
    }

    public int right() {
        return this.x2;
    }

    public int bottom() {
        return this.y2;
    }
}
