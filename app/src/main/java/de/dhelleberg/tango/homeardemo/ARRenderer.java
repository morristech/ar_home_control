/*
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.dhelleberg.tango.homeardemo;

import android.content.Context;
import android.util.Log;
import android.view.MotionEvent;

import com.google.atap.tangoservice.TangoCameraIntrinsics;
import com.google.atap.tangoservice.TangoPoseData;
import com.projecttango.rajawali.DeviceExtrinsics;
import com.projecttango.rajawali.Pose;
import com.projecttango.rajawali.ScenePoseCalculator;

import org.rajawali3d.lights.DirectionalLight;
import org.rajawali3d.materials.Material;
import org.rajawali3d.materials.methods.DiffuseMethod;
import org.rajawali3d.materials.textures.ATexture;
import org.rajawali3d.materials.textures.StreamingTexture;
import org.rajawali3d.math.Matrix4;
import org.rajawali3d.math.Quaternion;
import org.rajawali3d.math.vector.Vector3;
import org.rajawali3d.primitives.Plane;
import org.rajawali3d.primitives.ScreenQuad;
import org.rajawali3d.renderer.RajawaliRenderer;

import java.util.List;

import javax.microedition.khronos.opengles.GL10;


public class ARRenderer extends RajawaliRenderer {
    private static final float CUBE_SIDE_LENGTH = 0.5f;
    private static final String TAG = ARRenderer.class.getSimpleName();
    private final List<Item> itemlist;

    // Augmented Reality related fields
    private ATexture mTangoCameraTexture;
    private boolean mSceneCameraConfigured;

    private Plane mObject;
    private Pose mObjectPose;
    private boolean mObjectPoseUpdated = false;
    private double upDown;
    private double leftRight;
    private boolean positionUpdate = false;
    private boolean scaleUpdate = false;
    private double scaleX;
    private double scaleY;
    private Plane newPlane = null;
    private Material planeMaterial;

    public ARRenderer(Context context, List<Item> itemlist) {
        super(context);
        this.itemlist = itemlist;
    }

    @Override
    protected void initScene() {
        // Create a quad covering the whole background and assign a texture to it where the
        // Tango color camera contents will be rendered.
        ScreenQuad backgroundQuad = new ScreenQuad();
        Material tangoCameraMaterial = new Material();
        tangoCameraMaterial.setColorInfluence(0);
        // We need to use Rajawali's {@code StreamingTexture} since it sets up the texture
        // for GL_TEXTURE_EXTERNAL_OES rendering
        mTangoCameraTexture =
                new StreamingTexture("camera", (StreamingTexture.ISurfaceListener) null);
        try {
            tangoCameraMaterial.addTexture(mTangoCameraTexture);
            backgroundQuad.setMaterial(tangoCameraMaterial);
        } catch (ATexture.TextureException e) {
            Log.e(TAG, "Exception creating texture for RGB camera contents", e);
        }
        getCurrentScene().addChildAt(backgroundQuad, 0);

        // Add a directional light in an arbitrary direction.
        DirectionalLight light = new DirectionalLight(1, 0.2, -1);
        light.setColor(1, 1, 1);
        light.setPower(0.8f);
        light.setPosition(3, 2, 4);
        getCurrentScene().addLight(light);

        // Set-up a planeMaterial: green with application of the light and
        // instructions.
        planeMaterial = new Material();
        planeMaterial.setColor(0xAA009900);
        planeMaterial.enableLighting(true);
        planeMaterial.setDiffuseMethod(new DiffuseMethod.Lambert());

        // Build a Cube and place it initially in the origin.
        mObject = new Plane(1f, 1f, 1,1);
        mObject.setPosition(0, 0, -3);
        mObject.setRotation(Vector3.Axis.Z, 180);
        mObject.setMaterial(planeMaterial);
        mObject.setDoubleSided(false);
        mObject.setTransparent(true);

        getCurrentScene().addChild(mObject);


        //add all items
        for (int i = 0; i < itemlist.size(); i++) {
            Item item = itemlist.get(i);
            Plane plane = new Plane(1f, 1f, 1, 1);
            plane.setPosition(item.pos_x, item.pos_y, item.pos_z);
            plane.setScale(item.scale_x, item.scale_y, 1);
            plane.setOrientation(new Quaternion(item.quat_w, item.quat_x, item.quat_y, item.quat_y));
            plane.setMaterial(planeMaterial);
            plane.setDoubleSided(false);
            plane.setTransparent(true);
            getCurrentScene().addChild(plane);
        }
    }

    @Override
    protected void onRender(long elapsedRealTime, double deltaTime) {
        // Update the AR object if necessary
        // Synchronize against concurrent access with the setter below.
        synchronized (this) {
            if (mObjectPoseUpdated) {
                // Place the 3D object in the location of the detected plane.
                mObject.setPosition(mObjectPose.getPosition());
                mObject.setOrientation(mObjectPose.getOrientation());
                // Move it forward by half of the size of the cube to make it
                // flush with the plane surface.
//                mObject.moveForward(CUBE_SIDE_LENGTH / 2.0f);
                mObjectPoseUpdated = false;
            }
            if(positionUpdate) {
                mObject.moveUp(upDown);
                mObject.moveRight(leftRight);
                positionUpdate = false;
            }
            if(scaleUpdate) {
                mObject.setScaleX( mObject.getScaleX() + scaleX);
                mObject.setScaleY( mObject.getScaleY() + scaleY);
                scaleUpdate = false;
            }
        }

        super.onRender(elapsedRealTime, deltaTime);
    }

    /**
     * Save the updated plane fit pose to update the AR object on the next render pass.
     * This is synchronized against concurrent access in the render loop above.
     */
    public synchronized void updateObjectPose(TangoPoseData planeFitPose) {
        mObjectPose = ScenePoseCalculator.toOpenGLPose(planeFitPose);
        mObjectPoseUpdated = true;
    }

    /**
     * Update the scene camera based on the provided pose in Tango start of service frame.
     * The device pose should match the pose of the device at the time the last rendered RGB
     * frame, which can be retrieved with this.getTimestamp();
     * <p/>
     * NOTE: This must be called from the OpenGL render thread - it is not thread safe.
     */
    public void updateRenderCameraPose(TangoPoseData devicePose, DeviceExtrinsics extrinsics) {
        Pose cameraPose = ScenePoseCalculator.toOpenGlCameraPose(devicePose, extrinsics);
        getCurrentCamera().setRotation(cameraPose.getOrientation());
        getCurrentCamera().setPosition(cameraPose.getPosition());
    }

    /**
     * It returns the ID currently assigned to the texture where the Tango color camera contents
     * should be rendered.
     * NOTE: This must be called from the OpenGL render thread - it is not thread safe.
     */
    public int getTextureId() {
        return mTangoCameraTexture == null ? -1 : mTangoCameraTexture.getTextureId();
    }

    /**
     * We need to override this method to mark the camera for re-configuration (set proper
     * projection matrix) since it will be reset by Rajawali on surface changes.
     */
    @Override
    public void onRenderSurfaceSizeChanged(GL10 gl, int width, int height) {
        super.onRenderSurfaceSizeChanged(gl, width, height);
        mSceneCameraConfigured = false;
    }

    public boolean isSceneCameraConfigured() {
        return mSceneCameraConfigured;
    }

    /**
     * Sets the projection matrix for the scen camera to match the parameters of the color camera,
     * provided by the {@code TangoCameraIntrinsics}.
     */
    public void setProjectionMatrix(TangoCameraIntrinsics intrinsics) {
        Matrix4 projectionMatrix = ScenePoseCalculator.calculateProjectionMatrix(
                intrinsics.width, intrinsics.height,
                intrinsics.fx, intrinsics.fy, intrinsics.cx, intrinsics.cy);
        getCurrentCamera().setProjectionMatrix(projectionMatrix);
    }

    @Override
    public void onOffsetsChanged(float xOffset, float yOffset,
                                 float xOffsetStep, float yOffsetStep,
                                 int xPixelOffset, int yPixelOffset) {
    }

    @Override
    public void onTouchEvent(MotionEvent event) {

    }

    public void adjustPosition(double updown, double leftright) {
        Log.d(TAG, "adjustPosition upDown: "+updown+ " leftRight: "+leftright);
        this.upDown = updown;
        this.leftRight = leftright;
        this.positionUpdate = true;
    }

    public void adjustSize(double width, double height) {
        Log.d(TAG, "adjustScale width "+width+" height: "+height);
        this.scaleX = width;
        this.scaleY = height;
        this.scaleUpdate = true;
    }


    public Plane getCurrentEditObject() {
        return mObject;
    }


}
