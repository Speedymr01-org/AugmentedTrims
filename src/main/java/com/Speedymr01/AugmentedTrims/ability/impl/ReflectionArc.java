package com.Speedymr01.AugmentedTrims.ability.impl;

import com.Speedymr01.AugmentedTrims.ability.impl.QuartzRefract.Drag;

import org.bukkit.entity.Egg;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.WindCharge;
import org.bukkit.util.Vector;

/**
 * Solves the launch velocity that makes a projectile's vanilla physics land on a point.
 *
 * <p>Vanilla applies drag first, then gravity, each tick. Horizontal travel per unit velocity
 * over {@code T} ticks is {@code sum(d^i)}, so the required velocity is that distance divided by
 * the sum. Vertical motion is affine in the initial velocity, so two simulations (v=0 and v=1)
 * recover the exact required value rather than approximating the drop.
 */
public final class ReflectionArc {

    private ReflectionArc() {
    }

    public static Vector solve(Vector from, Vector to, int flightTicks, double gravity, double drag) {
        double reach = horizontalReach(flightTicks, drag);
        if (reach <= 1.0e-6) {
            Vector fallback = to.clone().subtract(from);
            return fallback.lengthSquared() < 1.0e-6 ? new Vector(0, 0, 0) : fallback.normalize();
        }

        double vx = (to.getX() - from.getX()) / reach;
        double vz = (to.getZ() - from.getZ()) / reach;

        double y0 = verticalDisplacement(0.0, flightTicks, gravity, drag);
        double y1 = verticalDisplacement(1.0, flightTicks, gravity, drag);
        double slope = y1 - y0;
        double vy = Math.abs(slope) < 1.0e-6 ? 0.0 : (to.getY() - from.getY() - y0) / slope;

        return new Vector(vx, vy, vz);
    }

    /** Number of ticks to spend on the return arc; longer shots get longer flights. */
    public static int flightTicks(double distance, int min, int max) {
        int ticks = (int) Math.round(distance / 1.5);
        return Math.max(Math.min(min, max), Math.min(max, ticks));
    }

    public static double gravityFor(Entity entity) {
        return dragFor(entity).gravity();
    }

    public static Drag dragFor(Entity entity) {
        if (entity instanceof Fireball) {
            return new Drag(0.0, 0.99);
        }
        if (entity instanceof Snowball || entity instanceof Egg || entity instanceof WindCharge) {
            return new Drag(0.03, 0.99);
        }
        return new Drag(0.05, 0.99);
    }

    private static double horizontalReach(int ticks, double drag) {
        double position = 0.0;
        double velocity = 1.0;
        for (int i = 0; i < ticks; i++) {
            velocity *= drag;
            position += velocity;
        }
        return position;
    }

    private static double verticalDisplacement(double initial, int ticks, double gravity, double drag) {
        double position = 0.0;
        double velocity = initial;
        for (int i = 0; i < ticks; i++) {
            velocity *= drag;
            velocity -= gravity;
            position += velocity;
        }
        return position;
    }
}
