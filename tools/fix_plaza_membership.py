from pathlib import Path
r=Path('src/main/java/de/villagerplus')
p=r/'village/VillageMap.java';s=p.read_text(encoding='utf-8').replace('private static final int SIZE = 4','private static final int SIZE = 8').replace('4x4 Bloecke','8x8 Bloecke').replace('.inflate(48)', '.inflate(Config.VILLAGE_RADIUS)');s=s.replace('int originX = bell.getX()', 'BlockPos mapCenter=Market.of(ref).plaza().map(p->new BlockPos((bell.getX()+p.getX())/2,bell.getY(),(bell.getZ()+p.getZ())/2)).orElse(bell);\n\t\tint originX = mapCenter.getX()').replace('int originZ = bell.getZ()','int originZ = mapCenter.getZ()');p.write_text(s,encoding='utf-8')
p=r/'client/BoardScreen.java';s=p.read_text(encoding='utf-8').replace('1 Feld = 4×4 Blöcke','1 Feld = 8×8 Blöcke');p.write_text(s,encoding='utf-8')
p=r/'job/JobManager.java';s=p.read_text(encoding='utf-8');a=s.index('\t\tdouble dx = c.v().getX() - home.getX();',s.index('private static boolean rescueStranded'));b=s.index('\n\t\tif (!far && !deep)',a);s=s[:a]+'''		double distance=Double.MAX_VALUE;
		int lowest=home.getY();
		java.util.List<net.minecraft.core.BlockPos> anchors=new java.util.ArrayList<>(java.util.List.of(home,ref.bell()));
		de.villagerplus.village.Market.of(ref).plaza().ifPresent(anchors::add);
		for(var anchor:anchors) {
			double dx=c.v().getX()-anchor.getX(),dz=c.v().getZ()-anchor.getZ();
			distance=Math.min(distance,dx*dx+dz*dz);lowest=Math.min(lowest,anchor.getY());
		}
		boolean far=distance>Config.VILLAGE_RADIUS*(double)Config.VILLAGE_RADIUS;
		boolean deep=c.v().getY()<lowest-12;
''' +s[b:];p.write_text(s,encoding='utf-8')
