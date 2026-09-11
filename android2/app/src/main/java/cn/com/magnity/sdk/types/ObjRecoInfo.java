package cn.com.magnity.sdk.types;

/* loaded from: classes.dex */
public class ObjRecoInfo implements Cloneable {
    public short alarmLevel;
    public int bodyTemp;
    public short confidence;
    public short irBottom;
    public short irLeft;
    public short irRight;
    public short irTop;
    public byte maskStatus;
    public int objId;
    public short objType;
    public byte quality;
    public int reserved2;
    public int reserved3;
    public int reserved4;
    public short samplePointIrX;
    public short samplePointIrY;
    public short samplePointVisX;
    public short samplePointVisY;
    public int temperature;
    public short visBottom;
    public short visLeft;
    public short visRight;
    public short visTop;

    /* renamed from: clone, reason: merged with bridge method [inline-methods] */
    public ObjRecoInfo m7clone() throws CloneNotSupportedException {
        super.clone();
        ObjRecoInfo obj = new ObjRecoInfo();
        obj.objId = this.objId;
        obj.objType = this.objType;
        obj.confidence = this.confidence;
        obj.irLeft = this.irLeft;
        obj.irTop = this.irTop;
        obj.irRight = this.irRight;
        obj.irBottom = this.irBottom;
        obj.samplePointIrX = this.samplePointIrX;
        obj.samplePointIrY = this.samplePointIrY;
        obj.visLeft = this.visLeft;
        obj.visTop = this.visTop;
        obj.visRight = this.visRight;
        obj.visBottom = this.visBottom;
        obj.samplePointVisX = this.samplePointVisX;
        obj.samplePointVisY = this.samplePointVisY;
        obj.temperature = this.temperature;
        obj.bodyTemp = this.bodyTemp;
        obj.alarmLevel = this.alarmLevel;
        obj.maskStatus = this.maskStatus;
        obj.quality = this.quality;
        obj.reserved2 = this.reserved2;
        obj.reserved3 = this.reserved3;
        obj.reserved4 = this.reserved4;
        return obj;
    }
}
