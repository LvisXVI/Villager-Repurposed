exec(open('tools/live090.py',encoding='utf-8-sig').read().split("a=cmd(")[0])
a=cmd('data get block 7333 78 -423 "fabric:attachments"."villagerplus:market"')
for parts in re.findall(r'owner: \[I; ([^\]]+)\]',a):
 v=0
 for number in parts.split(','):v=(v<<32)|(int(number.strip())&0xffffffff)
 ident=str(uuid.UUID(int=v))
 print(cmd(f'data modify entity {ident} Age set value -80'))
for c in ['data modify block 7333 78 -423 "fabric:attachments"."villagerplus:village".pop_at_last_house set value 48',
 'data modify block 7333 78 -423 "fabric:attachments"."villagerplus:village".next_build set value 0',
 'summon minecraft:villager 7390 67 -445 {Age:-24000,Tags:["vp_growth_test"]}',
 'summon minecraft:villager 7390 67 -444 {Age:-24000,Tags:["vp_growth_test"]}',
 'time set 1500','tick rate 100','gamemode spectator Player246','tp Player246 7420 91 -450 90 38']:
 print(cmd(c))
