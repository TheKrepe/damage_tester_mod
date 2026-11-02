package net.star1mp.damagetester.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;

/** Armor worn by the tester; follows exactly the same pose as {@link DamageTesterModel}. */
public class DamageTesterArmorModel extends HumanoidModel<DamageTesterRenderState> {
    public DamageTesterArmorModel(ModelPart root) {
        super(root);
    }

    @Override
    public void setupAnim(DamageTesterRenderState state) {
        this.resetPose();
        DamageTesterModel.applyPose(this, state);
    }
}
