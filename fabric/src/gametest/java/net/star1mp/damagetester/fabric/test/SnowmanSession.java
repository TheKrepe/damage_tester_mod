package net.star1mp.damagetester.fabric.test;

import java.nio.file.Path;
import java.util.Random;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.star1mp.damagetester.entity.DamageTesterEntity;
import net.star1mp.damagetester.entity.TesterVariant;
import net.star1mp.damagetester.fabric.test.trailer.Cine;

/** A staging world in the client game-test source set, independent of the release JAR. */
public final class SnowmanSession implements FabricClientGameTest {
    private int x0, y0, z0;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.waitTicks(20);
        ctx.getInput().resizeWindow(1920, 1080);
        try (TestSingleplayerContext world = ctx.worldBuilder()
                .adjustSettings(s -> {
                    s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                    s.getGameRules().set(GameRules.ADVANCE_TIME, false, null);
                    s.getGameRules().set(GameRules.ADVANCE_WEATHER, false, null);
                    s.getGameRules().set(GameRules.SPAWN_MOBS, false, null);
                    s.getGameRules().set(GameRules.SEND_COMMAND_FEEDBACK, false, null);
                }).create()) {
            world.getServer().runCommand("time set 2500");
            world.getServer().runCommand("weather clear");
            
            world.getServer().runOnServer(server -> {
                var level = world.getConnection().getServerLevel();
                var start = world.getConnection().getServerPlayer().blockPosition();
                x0=start.getX(); z0=start.getZ(); y0=level.getHeight(Heightmap.Types.MOTION_BLOCKING, x0,z0)+2;
                build(level);
                var player=world.getConnection().getServerPlayer();
                var hand=net.minecraft.world.InteractionHand.MAIN_HAND;
                var snow=DamageTesterEntity.spawnFromItem(TesterVariant.STANDARD,level,ItemStack.EMPTY,null,pos(-1.25,0,0),180F);
                var dressed=DamageTesterEntity.spawnFromItem(TesterVariant.STANDARD,level,ItemStack.EMPTY,null,pos(1.25,0,0.15),180F);
                player.setItemInHand(hand,new ItemStack(net.minecraft.world.item.Items.SNOW_BLOCK));
                snow.interact(player,hand,Vec3.ZERO);
                var tag=new ItemStack(net.minecraft.world.item.Items.NAME_TAG);
                tag.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,player.getName());
                player.setItemInHand(hand,tag);
                dressed.interact(player,hand,Vec3.ZERO);
                if(!snow.getSkin().equals(DamageTesterEntity.SKIN_SNOW) || !dressed.getSkin().startsWith(DamageTesterEntity.SKIN_PLAYER_PREFIX)) throw new AssertionError("Skin interaction failed");
                put(level,3,0,1,Blocks.ANVIL);
            });
            world.getServer().runCommand("gamemode spectator @a");
            world.getServer().runCommand("tp @a "+(x0+0.5)+" "+(y0+1)+" "+(z0-4.5)+" 0 0");
            ctx.waitTicks(30);
            ctx.runOnClient(mc -> {
                mc.options.fov().set(41);
                mc.options.fovEffectScale().set(0.0);
                mc.gui.hud.getChat().clearMessages(false);
                mc.gui.toastManager().clear();
                if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
            });
            still(ctx,world,"01_Winter_wardrobe",pos(-0.15,1.35,-5.2),pos(0,1,0));
            still(ctx,world,"02_Snowman",pos(-2.30,1.16,-3.05),pos(-1.25,0.99,0));
            still(ctx,world,"03_Player_skin",pos(2.35,1.36,-3.1),pos(1.25,1.02,0.15));
            world.getServer().runCommand("advancement revoke @a everything");
            world.getServer().runCommand("advancement grant @a from damage_tester:training/root");
            ctx.waitTicks(30);
            ctx.runOnClient(mc->{mc.gui.toastManager().clear(); });
            ctx.waitTicks(12);
            ctx.getInput().pressKey(options->options.keyAdvancements);
            ctx.waitTicks(20);
            ctx.runOnClient(mc->{if(mc.screen==null || !mc.screen.getClass().getSimpleName().contains("Advancement")) throw new AssertionError("Missing advancement screen: "+mc.screen);mc.gui.toastManager().clear();});
            ctx.takeScreenshot("04_Training_advancements");
        }
    }

    private void still(ClientGameTestContext ctx, TestSingleplayerContext world,String name,Vec3 eye,Vec3 look) {
        var pose=Cine.Pose.look(eye,look);
        for(int i=0;i<14;i++){ctx.runOnClient(mc->Cine.placeCamera(mc,pose));ctx.waitTick();}
        world.getConnection().waitForChunksRender(6000);
        ctx.runOnClient(mc->{Cine.placeCamera(mc,pose);mc.gui.toastManager().clear();});
        ctx.waitTick();
        ctx.takeScreenshot(name);
    }
    private Vec3 pos(double x,double y,double z){return new Vec3(x0+0.5+x,y0+y,z0+0.5+z);}
    private void put(ServerLevel l,int x,int y,int z,Block b){
        l.setBlock(new BlockPos(x0+x,y0+y,z0+z),b.defaultBlockState(),2);
    }
    private int bank(int x,int z){
        double back=Math.max(0,z-13)*0.17;
        double side=Math.max(0,Math.abs(x)-7)*0.13;
        double rolls=Math.sin(x*0.18+z*0.13)*0.65+Math.cos(z*0.22-x*0.08)*0.35;
        return (int)Math.max(0,Math.floor((back+side)*(1+0.42*rolls)));
    }
    private void build(ServerLevel l){
        Random rand=new Random(49262);
        for(int z=-17;z<=49;z++)for(int x=-37;x<=37;x++){
            int h=bank(x,z);
            for(int y=-3;y<h-1;y++)put(l,x,y,z,Blocks.DIRT);
            put(l,x,h-1,z,Blocks.SNOW_BLOCK);
            int path=(int)Math.round(3.7+Math.sin(z*0.27)*1.3);
            if(z<12 && z>-10 && Math.abs(x-path)<=1 && rand.nextDouble()<0.84)
                put(l,x,h-1,z,rand.nextBoolean()?Blocks.COARSE_DIRT:Blocks.GRAVEL);
            else if(x*x+z*z>5 && rand.nextDouble()<0.27)put(l,x,h,z,Blocks.SNOW);
        }
        // A staggered, dark backdrop with sky showing through the crowns.
        for(int z=9;z<46;z+=6)for(int x=-31;x<34;x+=6){
            int xx=x+rand.nextInt(5)-2,zz=z+rand.nextInt(4);
            if(xx>=3 && xx<=14 && zz<17) continue;
            pine(l,xx,zz,7+rand.nextInt(6),rand);
        }
        pine(l,-9,3,8,rand);pine(l,15,2,10,rand);
        cabin(l);
        for(int[] p:new int[][]{{-5,4},{-4,5},{-6,6},{3,2},{-8,10},{13,5},{6,16}}){
            int h=bank(p[0],p[1]);
            put(l,p[0],h,p[1],Blocks.ANDESITE);put(l,p[0],h+1,p[1],Blocks.SNOW);
        }
    }
    private void pine(ServerLevel l,int x,int z,int h,Random r){
        int g=bank(x,z);
        for(int y=0;y<h;y++)put(l,x,g+y,z,Blocks.SPRUCE_LOG);
        for(int k=2;k<=h;k++){
            int radius=k==h?0:Math.min(3,(h-k+2)/3+(k%3==0?1:0));
            for(int a=-radius;a<=radius;a++)for(int b=-radius;b<=radius;b++){
                if(Math.abs(a)+Math.abs(b)>radius*2-1 && radius>0 || a==0&&b==0&&k<h)continue;
                if(Math.abs(a)+Math.abs(b)>radius && r.nextDouble()<0.23)continue;
                var at=new BlockPos(x0+x+a,y0+g+k,z0+z+b);
                l.setBlock(at,Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT,true),2);
                if((k%3==0 || k==h) && r.nextDouble()<0.80)put(l,x+a,g+k+1,z+b,Blocks.SNOW);
            }
        }
    }
    private void cabin(ServerLevel l){
        for(int z=8;z<=14;z++)for(int x=5;x<=11;x++){
            put(l,x,0,z,Blocks.COBBLESTONE);
            if(x==5||x==11||z==8||z==14){
                for(int y=1;y<=3;y++)put(l,x,y,z,(x==5||x==11)&&(z==8||z==14)?Blocks.SPRUCE_LOG:Blocks.SPRUCE_PLANKS);
            }
        }
        for(int x:new int[]{6,7,9}){put(l,x,2,8,Blocks.GLASS);put(l,x,2,9,Blocks.SHROOMLIGHT);}
        for(int x=4;x<=12;x++){int y=4+Math.min(x-4,12-x);
            for(int z=7;z<=15;z++){put(l,x,y,z,Blocks.DARK_OAK_PLANKS);put(l,x,y+1,z,Blocks.SNOW);}
            for(int j=4;j<y;j++)put(l,x,j,8,Blocks.SPRUCE_PLANKS);
        }
        for(int i=0;i<8;i++)put(l,10,i+1,12,Blocks.STONE_BRICKS);
        put(l,10,9,12,Blocks.CAMPFIRE);
        for(int x:new int[]{5,11}){put(l,x,1,7,Blocks.SPRUCE_FENCE);put(l,x,2,7,Blocks.LANTERN);}
    }
}
