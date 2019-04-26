package com.replaymod.render.capturer;

import com.replaymod.core.utils.EventRegistrations;
import com.replaymod.render.RenderSettings;
import com.replaymod.render.frame.CubicOpenGlFrame;
import com.replaymod.render.frame.ODSOpenGlFrame;
import com.replaymod.render.frame.OpenGlFrame;
import com.replaymod.render.hooks.FogStateCallback;
import com.replaymod.render.hooks.Texture2DStateCallback;
import com.replaymod.render.rendering.FrameCapturer;
import com.replaymod.render.shader.Program;
import de.johni0702.minecraft.gui.utils.lwjgl.ReadableDimension;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.ReportedException;
import net.minecraft.util.ResourceLocation;

import static net.minecraft.client.renderer.GlStateManager.*;

import java.io.IOException;

import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;

public class ODSFrameCapturer implements FrameCapturer<ODSOpenGlFrame> {
    private static final ResourceLocation vertexResource = new ResourceLocation("replaymod", "shader/ods.vert");
    private static final ResourceLocation fragmentResource = new ResourceLocation("replaymod", "shader/ods.frag");

    private final CubicPboOpenGlFrameCapturer left, right;
    private final Program shaderProgram;
    private final Program.Uniform directionVariable;
    private final Program.Uniform leftEyeVariable;

    private EventRegistrations renderStateEvents;

    public ODSFrameCapturer(WorldRenderer worldRenderer, final RenderInfo renderInfo, int frameSize) {
        RenderInfo fakeInfo = new RenderInfo() {
            private int call;
            private float partialTicks;
            @Override
            public ReadableDimension getFrameSize() {
                return renderInfo.getFrameSize();
            }

            @Override
            public int getFramesDone() {
                return renderInfo.getFramesDone();
            }

            @Override
            public int getTotalFrames() {
                return renderInfo.getTotalFrames();
            }

            @Override
            public float updateForNextFrame() {
                if (call++ % 2 == 0) {
                    unbindProgram();
                    partialTicks = renderInfo.updateForNextFrame();
                    bindProgram();
                }
                return partialTicks;
            }

            @Override
            public RenderSettings getRenderSettings() {
                return renderInfo.getRenderSettings();
            }
        };
        left = new CubicStereoFrameCapturer(worldRenderer, fakeInfo, frameSize);
        right = new CubicStereoFrameCapturer(worldRenderer, fakeInfo, frameSize);
        try {
            shaderProgram = new Program(vertexResource, fragmentResource);
            leftEyeVariable = shaderProgram.getUniformVariable("leftEye");
            directionVariable = shaderProgram.getUniformVariable("direction");
        } catch (Exception e) {
            throw new ReportedException(CrashReport.makeCrashReport(e, "Creating ODS shaders"));
        }
    }

    private void bindProgram() {
        shaderProgram.use();
        setTexture("texture", 0);
        setTexture("lightMap", 1);

        renderStateEvents = new EventRegistrations();
        Program.Uniform[] texture2DUniforms = new Program.Uniform[]{
                shaderProgram.getUniformVariable("textureEnabled"),
                shaderProgram.getUniformVariable("lightMapEnabled"),
                shaderProgram.getUniformVariable("hurtTextureEnabled")
        };
        renderStateEvents.on(Texture2DStateCallback.EVENT, (id, enabled) -> {
            if (id > 0 && id < texture2DUniforms.length) {
                texture2DUniforms[id].set(true);
            }
        });
        Program.Uniform fogUniform = shaderProgram.getUniformVariable("fogEnabled");
        renderStateEvents.on(FogStateCallback.EVENT, fogUniform::set);

        renderStateEvents.register();
    }

    private void unbindProgram() {
        renderStateEvents.unregister();
        renderStateEvents = null;
        shaderProgram.stopUsing();
    }

    private void setTexture(String texture, int i) {
        shaderProgram.getUniformVariable(texture).set(i);
    }

    @Override
    public boolean isDone() {
        return left.isDone() && right.isDone();
    }

    @Override
    public ODSOpenGlFrame process() {
        bindProgram();
        leftEyeVariable.set(true);
        CubicOpenGlFrame leftFrame = left.process();
        leftEyeVariable.set(false);
        CubicOpenGlFrame rightFrame = right.process();
        unbindProgram();

        if (leftFrame != null && rightFrame != null) {
            return new ODSOpenGlFrame(leftFrame, rightFrame);
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        left.close();
        right.close();
        shaderProgram.delete();
    }

    private class CubicStereoFrameCapturer extends CubicPboOpenGlFrameCapturer {
        public CubicStereoFrameCapturer(WorldRenderer worldRenderer, RenderInfo renderInfo, int frameSize) {
            super(worldRenderer, renderInfo, frameSize);
        }

        @Override
        protected OpenGlFrame renderFrame(int frameId, float partialTicks, CubicOpenGlFrameCapturer.Data captureData) {
            resize(getFrameWidth(), getFrameHeight());

            pushMatrix();
            frameBuffer().bindFramebuffer(true);

            clear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            enableTexture2D();

            directionVariable.set(captureData.ordinal());
            worldRenderer.renderWorld(partialTicks, null);

            frameBuffer().unbindFramebuffer();
            popMatrix();

            return captureFrame(frameId, captureData);
        }
    }
}
