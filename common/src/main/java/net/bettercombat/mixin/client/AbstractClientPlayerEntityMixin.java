package net.bettercombat.mixin.client;

import com.mojang.authlib.GameProfile;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import net.bettercombat.client.AnimationRegistry;
import net.bettercombat.client.BetterCombatClient;
import net.bettercombat.client.PlayerAttackAnimatable;
import net.bettercombat.client.animation.*;
import net.bettercombat.logic.PlayerAttackHelper;
import net.bettercombat.logic.WeaponRegistry;
import net.bettercombat.mixin.LivingEntityAccessor;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.encryption.PlayerPublicKey;
import net.minecraft.util.Arm;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

@Mixin(AbstractClientPlayerEntity.class)
public abstract class AbstractClientPlayerEntityMixin extends PlayerEntity implements PlayerAttackAnimatable {
    private final PoseSubStack mainHandPose = new PoseSubStack(createPoseAdjustment(), true);
    private final PoseSubStack offHandPose = new PoseSubStack(null, false);
    private final AttackAnimationSubStack attackAnimation = new AttackAnimationSubStack(createAttackAdjustment());

    public AbstractClientPlayerEntityMixin(World world, BlockPos pos, float yaw, GameProfile gameProfile, @org.jetbrains.annotations.Nullable PlayerPublicKey publicKey) {
        super(world, pos, yaw, gameProfile, publicKey);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void postInit(ClientWorld world, GameProfile profile, PlayerPublicKey publicKey, CallbackInfo ci) {
        var stack = ((IAnimatedPlayer) this).getAnimationStack();
        stack.addAnimLayer(1, offHandPose.base);
        stack.addAnimLayer(2, mainHandPose.base);
        stack.addAnimLayer(2000, attackAnimation.base);

        mainHandPose.configure = this::updateAnimationByCurrentActivity;
        offHandPose.configure = this::updateAnimationByCurrentActivity;
    }

    @Override
    public void updateAnimationsOnTick() {
        var instance = (Object)this;
        var player = (PlayerEntity)instance;
        var isLeftHanded = isLeftHanded();

        // No pose during mining or item usage

        if (player.handSwinging || player.isUsingItem()) {
            offHandPose.setPose(null, isLeftHanded);
            mainHandPose.setPose(null, isLeftHanded);
            return;
        }

        // Restore auto body rotation upon swing - Fix issue #11

        if (getCurrentAnimation().isPresent() && getCurrentAnimation().get().isActive()) {
            ((LivingEntityAccessor)player).invokeTurnHead(player.getHeadYaw(), 0);
        }

        // Pose

        KeyframeAnimation newMainHandPose = null;
        var mainHandAttributes = WeaponRegistry.getAttributes(player.getMainHandStack());
        if (mainHandAttributes != null && mainHandAttributes.pose() != null) {
            newMainHandPose = AnimationRegistry.animations.get(mainHandAttributes.pose());
        }
        mainHandPose.setPose(newMainHandPose, isLeftHanded);

        KeyframeAnimation newOffHandPose = null;
        if (PlayerAttackHelper.isDualWielding(player)) {
            var offHandAttributes = WeaponRegistry.getAttributes(player.getOffHandStack());
            if (offHandAttributes != null && offHandAttributes.offHandPose() != null) {
                newOffHandPose = AnimationRegistry.animations.get(offHandAttributes.offHandPose());
            }
        }
        offHandPose.setPose(newOffHandPose, isLeftHanded);
    }

    @Override
    public void playAttackAnimation(String name, boolean isOffHand, float length) {
        try {
            KeyframeAnimation animation = AnimationRegistry.animations.get(name);
            var copy = animation.mutableCopy();
            updateAnimationByCurrentActivity(copy);
            copy.torso.fullyEnablePart(true);
            copy.head.pitch.setEnabled(false);
            var speed = ((float)animation.endTick) / length;
            var mirror = isOffHand;
            if(isLeftHanded()) {
                mirror = !mirror;
            }

            var fadeIn = copy.beginTick;
            attackAnimation.speed.speed = speed;
            attackAnimation.mirror.setEnabled(mirror);
            if (BetterCombatClient.config.isSmoothAnimationTransitionEnabled) {
                attackAnimation.base.replaceAnimationWithFade(
                        AbstractFadeModifier.standardFadeIn(fadeIn, Ease.INOUTSINE),
                        new CustomAnimationPlayer(copy.build(), 0));
            } else {
                attackAnimation.base.setAnimation(new CustomAnimationPlayer(copy.build(), 0));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private AdjustmentModifier createAttackAdjustment() {
        var player = (PlayerEntity)this;
        return new AdjustmentModifier((partName) -> {
            // System.out.println("Player pitch: " + player.getPitch());
            float rotationX = 0;
            float rotationY = 0;
            float rotationZ = 0;
            float offsetX = 0;
            float offsetY = 0;
            float offsetZ = 0;

            if (FirstPersonRenderHelper.isRenderingFirstPersonPlayerModel) {
                var pitch = player.getPitch();
                pitch = (float) Math.toRadians(pitch);
                switch (partName) {
                    case "rightArm", "leftArm" -> {
                        rotationX = pitch;
                    }
                    default -> {
                        return Optional.empty();
                    }
                }
            } else {
                var pitch = player.getPitch() / 2F;
                pitch = (float) Math.toRadians(pitch);
                switch (partName) {
                    case "body" -> {
                        rotationX = (-1F) * pitch;
                    }
                    case "rightArm", "leftArm" -> {
                        rotationX = pitch;
                    }
                    case "rightLeg", "leftLeg" -> {
                        rotationX = (-1F) * pitch;
                    }
                    default -> {
                        return Optional.empty();
                    }
                }
            }

            return Optional.of(new AdjustmentModifier.PartModifier(
                    new Vec3f(rotationX, rotationY, rotationZ),
                    new Vec3f(offsetX, offsetY, offsetZ))
            );
        });
    }

    private AdjustmentModifier createPoseAdjustment() {
        var player = (PlayerEntity)this;
        return new AdjustmentModifier((partName) -> {
            float rotationX = 0;
            float rotationY = 0;
            float rotationZ = 0;
            float offsetX = 0;
            float offsetY = 0;
            float offsetZ = 0;

            if (!FirstPersonRenderHelper.isRenderingFirstPersonPlayerModel) {
                switch (partName) {
                    case "rightArm", "leftArm" -> {
                        if (player.isSneaking()) {
                            offsetY += 4;
                        }
                    }
                    default -> {
                        return Optional.empty();
                    }
                }
            }

            return Optional.of(new AdjustmentModifier.PartModifier(
                    new Vec3f(rotationX, rotationY, rotationZ),
                    new Vec3f(offsetX, offsetY, offsetZ))
            );
        });
    }

    private void updateAnimationByCurrentActivity(KeyframeAnimation.AnimationBuilder animation) {
        var pose = getPose();
        switch (pose) {
            case STANDING -> {
            }
            case FALL_FLYING -> {
            }
            case SLEEPING -> {
            }
            case SWIMMING -> {
                configurBodyPart(animation.rightLeg, false, false);
                configurBodyPart(animation.leftLeg, false, false);
            }
            case SPIN_ATTACK -> {
            }
            case CROUCHING -> {
//                configurBodyPart(animation.head, true, false);
//                configurBodyPart(animation.rightArm, true, false);
//                configurBodyPart(animation.leftArm, true, false);
//                configurBodyPart(animation.rightLeg, false, false);
//                configurBodyPart(animation.leftLeg, false, false);
            }
            case LONG_JUMPING -> {
            }
            case DYING -> {
            }
        }
        if (isMounting()) {
            configurBodyPart(animation.rightLeg, false, false);
            configurBodyPart(animation.leftLeg, false, false);
        }
    }

    private static void configurBodyPart(KeyframeAnimation.StateCollection bodyPart, boolean isRotationEnabled, boolean isOffsetEnabled) {
        bodyPart.pitch.setEnabled(isRotationEnabled);
        bodyPart.roll.setEnabled(isRotationEnabled);
        bodyPart.yaw.setEnabled(isRotationEnabled);
        bodyPart.x.setEnabled(isOffsetEnabled);
        bodyPart.y.setEnabled(isOffsetEnabled);
        bodyPart.z.setEnabled(isOffsetEnabled);
    }

    @Override
    public void stopAttackAnimation() {
        IAnimation currentAnimation = attackAnimation.base.getAnimation();
        if (currentAnimation != null && currentAnimation instanceof KeyframeAnimationPlayer) {
            if (BetterCombatClient.config.isSmoothAnimationTransitionEnabled) {
                attackAnimation.base.replaceAnimationWithFade(
                        AbstractFadeModifier.standardFadeIn(5, Ease.INOUTSINE), null);
            } else {
                ((KeyframeAnimationPlayer)currentAnimation).stop();
            }
        }
    }

    private boolean isMounting() {
        return this.getVehicle() != null;
    }

    public boolean isLeftHanded() {
        return this.getMainArm() == Arm.LEFT;
    }

    @Override
    public Optional<IAnimation> getCurrentAnimation() {
        return Optional.ofNullable(attackAnimation.base.getAnimation());
    }
}