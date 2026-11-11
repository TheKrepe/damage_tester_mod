package net.star1mp.damagetester.client;

import com.google.common.collect.ImmutableList;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.star1mp.damagetester.entity.DamageTesterEntity;
import net.star1mp.damagetester.entity.TesterVariant;

/**
 * The tester: a burlap-sack head and torso, straw arms stretched out to the sides and a
 * single wooden post instead of legs. The base plate is a separate model so it does not
 * tilt when the tester wobbles (see {@link DamageTesterRenderer}).
 *
 * <p>Part names and pivots are the standard humanoid ones so vanilla armor fits.</p>
 */
public class DamageTesterModel extends HumanoidModel<DamageTesterEntity> {
    public static final int TEXTURE_WIDTH = 64;
    public static final int TEXTURE_HEIGHT = 64;

    private final ModelPart rope;

    public DamageTesterModel(ModelPart root) {
        super(root);
        this.rope = root.getChild("rope");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F);
        PartDefinition root = mesh.getRoot();
        // Wooden post (uses the right leg slot), from the hips down to the top of the base plate.
        root.addOrReplaceChild("right_leg",
                CubeListBuilder.create().texOffs(0, 16).addBox(-1.0F, 0.0F, -1.0F, 2.0F, 10.0F, 2.0F),
                PartPose.offset(0.0F, 12.0F, 0.0F));
        // No second leg.
        root.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.offset(1.9F, 12.0F, 0.0F));
        // Rope above the head, only shown on the hanging tester.
        root.addOrReplaceChild("rope",
                CubeListBuilder.create().texOffs(56, 16).addBox(-0.5F, -12.0F, -0.5F, 1.0F, 4.0F, 1.0F),
                PartPose.ZERO);
        return LayerDefinition.create(mesh, TEXTURE_WIDTH, TEXTURE_HEIGHT);
    }

    /** A 12x2x12 plank base the post is nailed into. */
    public static LayerDefinition createBaseLayer() {
        MeshDefinition mesh = new MeshDefinition();
        mesh.getRoot().addOrReplaceChild("base",
                CubeListBuilder.create().texOffs(0, 32).addBox(-6.0F, 22.0F, -6.0F, 12.0F, 2.0F, 12.0F),
                PartPose.ZERO);
        return LayerDefinition.create(mesh, TEXTURE_WIDTH, TEXTURE_HEIGHT);
    }

    @Override
    protected Iterable<ModelPart> bodyParts() {
        return ImmutableList.of(this.body, this.rightArm, this.leftArm, this.rightLeg, this.leftLeg, this.hat, this.rope);
    }

    @Override
    public void setupAnim(DamageTesterEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        float partial = ageInTicks - entity.tickCount;
        float wobble = entity.getWobble(partial);
        float sway = entity.getSway(partial);
        reset(this.head, 0.0F, 0.0F);
        reset(this.body, 0.0F, 0.0F);
        // Arms stick straight out. The shoulders are moved 1px outwards and 1px down compared to a
        // player so the arm boxes start exactly at the edge and top of the torso instead of
        // overlapping it (that overlap made the textures flicker through each other).
        reset(this.rightArm, -6.0F, 3.0F);
        reset(this.leftArm, 6.0F, 3.0F);
        this.rightArm.zRot = (float) (Math.PI / 2.0);
        this.leftArm.zRot = (float) (-Math.PI / 2.0);
        // arms flop a little when hit
        this.rightArm.xRot = -wobble * 0.6F;
        this.leftArm.xRot = -wobble * 0.6F;
        this.head.xRot = -wobble * 0.45F;
        this.head.zRot = sway * 0.8F;
        this.hat.copyFrom(this.head);
        reset(this.rightLeg, 0.0F, 12.0F);
        reset(this.leftLeg, 1.9F, 12.0F);
        boolean hanging = entity.getVariant() == TesterVariant.HANGING;
        this.rope.visible = hanging;
        this.rightLeg.visible = !hanging;
    }

    private static void reset(ModelPart part, float x, float y) {
        part.setPos(x, y, 0.0F);
        part.xRot = 0.0F;
        part.yRot = 0.0F;
        part.zRot = 0.0F;
    }
}
