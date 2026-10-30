exec(open('tools/live090.py',encoding='utf-8-sig').read().split("a=cmd(")[0])
slots=[]
for x in [7396,7397]:
 data=cmd(f'data get block {x} 66 -446 Items');used={int(n) for n in re.findall(r'Slot: (\d+)b',data)}
 for i in range(27):
  if i not in used:slots.append((x,i))
for item,n in [('smooth_sandstone',256),('oak_log',128),('smooth_sandstone_stairs',128),('bread',128),('jungle_fence',64)]:
 while n>0 and slots:
  x,i=slots.pop(0);count=min(64,n);n-=count
  print(cmd(f'item replace block {x} 66 -446 container.{i} with minecraft:{item} {count}'))
print(cmd('time set 1500'));print(cmd('tick rate 300'))
