import glob,json,os,sys,numpy as np,cv2
sys.path.insert(0,'/home/delphic/win_share/Delphic/mag160_thermalcam/recon/triangulation')
from match_offline import grad_mag
d=sys.argv[1]; out=sys.argv[2]  # usage: scan_band.py <dump dir> <out dir>
dist=[0.6,1.2,1.8,2.4,3.0]
def sample(v,tw,th,zoom,dx,dy):
    vh,vw=v.shape
    xs=((np.arange(tw)+.5)/tw-.5)/zoom+.5+dx; ys=((np.arange(th)+.5)/th-.5)/zoom+.5+dy
    mx,my=np.meshgrid(xs*vw-.5,ys*vh-.5)
    return cv2.remap(v,mx.astype(np.float32),my.astype(np.float32),cv2.INTER_LINEAR,borderValue=float(v.mean()))
for k,j in enumerate(sorted(glob.glob(d+'/fd_*.json'))):
    b=j[:-5]; m=json.load(open(j)); tw,th,vw,vh=m['thermal_w'],m['thermal_h'],m['visible_w'],m['visible_h']
    t=np.fromfile(b+'.f32','<f4').reshape(th,tw); v=np.fromfile(b+'.u8',np.uint8).reshape(vh,vw).astype(np.float32)
    vb=cv2.GaussianBlur(v,(0,0),1.2)             # ~ visible px per thermal px / 2
    tg=grad_mag(t,1.5); thr=np.percentile(tg,90); mask=tg>=thr
    zoom=2.35
    dxs=np.arange(-0.10,0.041,0.004); dys=np.arange(-0.15,0.401,0.004)
    S=np.zeros((len(dys),len(dxs)))
    for a,dy in enumerate(dys):
        for c,dx in enumerate(dxs):
            vg=grad_mag(sample(vb,tw,th,zoom,dx,dy),0.7)
            S[a,c]=vg[mask].mean()/(vg.mean()+1e-6)
    a,c=np.unravel_index(np.argmax(S),S.shape)
    prof=S.max(axis=1)
    print(f'{os.path.basename(b)} ({dist[k]} m?): peak dx {dxs[c]:+.3f} dy {dys[a]:+.3f} score {S[a,c]:.2f} median {np.median(S):.2f} | app dx {m["dx"]:+.3f} dy {m["dy"]:+.3f}')
    np.save(out+f'/S_{k}.npy',S)
    img=cv2.applyColorMap(cv2.resize(((S-S.min())/(S.max()-S.min())*255).astype(np.uint8),(len(dxs)*4,len(dys)*2),interpolation=cv2.INTER_NEAREST),cv2.COLORMAP_VIRIDIS)
    cv2.imwrite(out+f'/S_{k}.png',img)
