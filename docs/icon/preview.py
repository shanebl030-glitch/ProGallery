from PIL import Image, ImageDraw
S=1728
bg=Image.open('bg_master.png').convert('RGBA'); fg=Image.open('fg_master.png'); mono=Image.open('mono_master.png')
full=bg.copy(); full.alpha_composite(fg)
vis=(S*18//108, S*18//108, S*90//108, S*90//108)
def masked(img, shape, size):
    im=img.crop(vis).resize((size,size), Image.LANCZOS)
    m=Image.new('L',(size*4,size*4),0); d=ImageDraw.Draw(m)
    if shape=='circle': d.ellipse((0,0,size*4-1,size*4-1),255)
    elif shape=='squircle': d.rounded_rectangle((0,0,size*4-1,size*4-1), radius=size*4*0.32, fill=255)
    else: d.rounded_rectangle((0,0,size*4-1,size*4-1), radius=size*4*0.18, fill=255)
    m=m.resize((size,size), Image.LANCZOS); out=Image.new('RGBA',(size,size)); out.paste(im,(0,0),m); return out
themed=Image.new('RGBA',(S,S),(232,224,248,255)); tint=Image.new('RGBA',(S,S),(59,42,122,255)); themed.paste(tint,(0,0),mono)
sheet=Image.new('RGBA',(820,1000),(30,30,36,255))
y=20
for shape in ['circle','squircle','rounded']:
    x=20
    for s in [192,96,64,48,36]:
        sheet.alpha_composite(masked(full,shape,s),(x,y+(192-s)//2)); x+=s+28
    y+=215
x=20
for s in [192,96,48]:
    sheet.alpha_composite(masked(themed,'circle',s),(x,y+(192-s)//2)); x+=s+28
y+=215
light=Image.new('RGBA',(780,130),(242,242,247,255)); x=20
for s in [96,64,48]:
    light.alpha_composite(masked(full,'circle',s),(x,(130-s)//2)); x+=s+40
sheet.alpha_composite(light,(20,y))
sheet.save('preview.png')
