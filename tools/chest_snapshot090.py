exec(open('tools/live090.py',encoding='utf-8-sig').read().split("a=cmd(")[0])
for x in [7396,7397]:
 data=cmd(f'data get block {x} 66 -446 Items');used=set(re.findall(r'Slot: (\d+)b',data));print(x,'occupied',len(used));print(data[:1800])
