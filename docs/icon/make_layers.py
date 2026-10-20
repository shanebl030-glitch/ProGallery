from PIL import Image, ImageFilter, ImageDraw
import numpy as np
src = Image.open('source.png').convert('RGB')
a = np.asarray(src).astype(np.float32)
# 1) Artwork region (cards + aperture), excluding frame and wordmark.
x0,y0,x1,y1 = 125,150,1115,865
crop = a[y0:y1, x0:x1]
mx = crop.max(axis=2)
def smooth(e0,e1,x):
    t=np.clip((x-e0)/(e1-e0),0,1); return t*t*(3-2*t)
# Background = dark pixels connected to the crop border (flood fill by iterative dilation).
R, G, B = crop[...,0], crop[...,1], crop[...,2]
_hh, _ww = R.shape
_gy, _gx = np.mgrid[0:_hh, 0:_ww]
_edge = np.minimum.reduce([_gx, _gy, _ww-1-_gx, _hh-1-_gy])
glow = (R < 70) & (B > G * 1.3) & (_edge < 90)   # frame glow only exists near the crop edge
dark = (mx < 115) | glow
bgm = np.zeros_like(dark)
bgm[0,:]=dark[0,:]; bgm[-1,:]=dark[-1,:]; bgm[:,0]=dark[:,0]; bgm[:,-1]=dark[:,-1]
while True:
    grown = bgm.copy()
    grown[1:,:] |= bgm[:-1,:]; grown[:-1,:] |= bgm[1:,:]; grown[:,1:] |= bgm[:,:-1]; grown[:,:-1] |= bgm[:,1:]
    grown &= dark
    if (grown == bgm).all(): break
    bgm = grown
art_mask = ~bgm
# The aperture's metallic ring is dark; keep the whole aperture disc.
hh, ww = mx.shape
gy, gx = np.mgrid[0:hh, 0:ww]
art_mask |= ((gx-(835-x0))**2 + (gy-(640-y0))**2) < 172**2
m = Image.fromarray((art_mask*255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.6))
alpha = np.asarray(m).astype(np.float32)/255
# Trim the crop's corners (where the original frame's rounded corners intrude).
rr = Image.new('L', (ww, hh), 0)
ImageDraw.Draw(rr).rounded_rectangle((0, 0, ww-1, hh-1), radius=210, fill=255)
alpha *= np.asarray(rr.filter(ImageFilter.GaussianBlur(6))).astype(np.float32)/255
# Feather the crop boundary so no rectangle edge can ever show.
h,w = alpha.shape
yy,xx = np.mgrid[0:h,0:w]
edge = np.minimum.reduce([xx, yy, w-1-xx, h-1-yy]).astype(np.float32)
alpha *= smooth(0, 18, edge)
fg_art = np.dstack([crop, alpha*255]).astype(np.uint8)
art = Image.fromarray(fg_art, 'RGBA')
# 2) Place on the 108dp canvas: artwork diagonal fits a ~70dp circle (safe zone is 66dp).
S = 1728  # 16 px per dp
diag = (w**2 + h**2) ** 0.5
scale = (70/108*S) / diag
art = art.resize((round(w*scale), round(h*scale)), Image.LANCZOS)
fg = Image.new('RGBA', (S,S), (0,0,0,0))
cx, cy = S//2, S//2
fg.alpha_composite(art, (cx - art.width//2, cy - art.height//2 + int(0.01*S)))
fg.save('fg_master.png')
# 3) Background: navy matched to the original's interior, with a soft blue glow.
bg = np.zeros((S,S,3),np.float32)
yy,xx = np.mgrid[0:S,0:S].astype(np.float32)/S
top = np.array([9,20,58]); bot = np.array([4,7,22])
t = (xx*0.35 + yy*0.65)[...,None]
bg = top*(1-t) + bot*t
r = np.sqrt((xx-0.5)**2+(yy-0.42)**2)[...,None]
bg += np.array([16,50,140]) * np.clip(1 - r/0.55, 0, 1)**2 * 0.30
Image.fromarray(np.clip(bg,0,255).astype(np.uint8)).save('bg_master.png')
# 4) Monochrome (themed icon): bright, low-saturation details (card borders, sun, play) + aperture.
cr = crop
v = cr.max(axis=2); mn = cr.min(axis=2); sat = (v-mn)/np.maximum(v,1)
mono_a = np.clip(smooth(150,215,v)*smooth(0.45,0.2,sat) + smooth(120,190,v)*0, 0, 1)
# add the aperture disc (centre ~ (835,640) in source, radius ~ 165)
ay, ax = 640-y0, 835-x0
dist = np.sqrt((xx[:h,:w]*0 + np.mgrid[0:h,0:w][1]-ax)**2 + (np.mgrid[0:h,0:w][0]-ay)**2)
ring = smooth(170,160,dist) * smooth(120,132,dist)  # outer ring
disc = smooth(118,108,dist) * smooth(30,42,dist)    # blades with centre hole
mono_a = np.maximum(mono_a*(dist>175), np.maximum(ring, disc))
mono_a *= smooth(0,18,edge)
mono = Image.fromarray(np.dstack([np.zeros_like(mono_a)]*3 + [mono_a*255]).astype(np.uint8), 'RGBA').resize(art.size, Image.LANCZOS)
mono_c = Image.new('RGBA',(S,S),(0,0,0,0)); mono_c.alpha_composite(mono,(cx-art.width//2, cy-art.height//2+int(0.01*S)))
mono_c.save('mono_master.png')
# 5) About-screen logo: the full original design (with wordmark), black surround made transparent.
full = np.asarray(src).astype(np.float32)
fa = smooth(6, 30, full.max(axis=2))
Image.fromarray(np.dstack([full, fa*255]).astype(np.uint8),'RGBA').resize((640,640), Image.LANCZOS).save('about_logo.png')
print('ok', art.size)
