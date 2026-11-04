package net.star1mp.damagetester.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.star1mp.damagetester.entity.TesterVariant;

/**
 * The tester: a burlap-sack head and torso, straw arms stretched out to the sides and a
 * single wooden post instead of legs. The base plate is a separate model so it does not
 * tilt when the tester wobbles (see {@link DamageTesterRenderer}).
 *
 * <p>Part names and pivots are the standard humanoid ones so vanilla armor fits.</p>
 */
public class DamageTesterModel extends HumanoidModel<DamageTesterRenderState> {
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
    public void setupAnim(DamageTesterRenderState state) {
        this.resetPose();
        applyPose(this, state);
        boolean hanging = state.variant == TesterVariant.HANGING;
        this.rope.visible = hanging;
        this.rightLeg.visible = !hanging;
    }

    /**
     * Shared by the body and the armor models so armor follows the same pose.
     * The big lean is applied to the whole pose stack by the renderer; here we only add
     * the scarecrow arms and a bit of extra head lag.
     */
    static void applyPose(HumanoidModel<DamageTesterRenderState> model, DamageTesterRenderState state) {
        // Arms stick straight out. The shoulders are moved 1px outwards and 1px down compared to a
        // player so the arm boxes start exactly at the edge and top of the torso instead of
        // overlapping it (that overlap made the textures flicker through each other).
        model.rightArm.setPos(-6.0F, 3.0F, 0.0F);
        model.leftArm.setPos(6.0F, 3.0F, 0.0F);
        model.rightArm.zRot = (float) (Math.PI / 2.0);
        model.leftArm.zRot = (float) (-Math.PI / 2.0);
        // arms flop a little when hit
        model.rightArm.xRot = -state.wobble * 0.6F;
        model.leftArm.xRot = -state.wobble * 0.6F;
        model.head.xRot = -state.wobble * 0.45F;
        model.head.zRot = state.sway * 0.8F;
        model.rightLeg.xRot = 0.0F;
        model.leftLeg.xRot = 0.0F;
    }
}
