package net.vulkanmod.vulkan.shader;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.vulkanmod.interfaces.VertexFormatMixed;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class GraphicsPipeline extends Pipeline {
    private final Object2LongMap<PipelineState> graphicsPipelines = new Object2LongOpenHashMap<>();

    private final VertexFormat vertexFormat;
    private final VertexInputDescription vertexInputDescription;
    protected final int[] colorAttachmentFormats;
    protected final int depthAttachmentFormat;
    private final boolean[] colorBlendDisabled;

    private long vertShaderModule = 0;
    private long fragShaderModule = 0;

    GraphicsPipeline(Builder builder) {
        super(builder.name);
        this.buffers = builder.UBOs;
        this.imageDescriptors = builder.imageDescriptors;
        this.pushConstants = builder.pushConstants;
        this.vertexFormat = builder.vertexFormat;
        this.colorAttachmentFormats = builder.colorAttachmentFormats;
        this.depthAttachmentFormat = builder.depthAttachmentFormat;
        this.colorBlendDisabled = builder.colorBlendDisabled;

        this.vertexInputDescription = new VertexInputDescription(this.vertexFormat);

        createDescriptorSetLayout();
        createPipelineLayout();

        createShaderModules(builder);

        if (builder.renderPass != null)
            graphicsPipelines.computeIfAbsent(PipelineState.DEFAULT,
                    this::createGraphicsPipeline);

        createDescriptorSets(Renderer.getFramesNum());

        PIPELINES.add(this);
    }

    public long getHandle(PipelineState state) {
        return graphicsPipelines.computeIfAbsent(state, this::createGraphicsPipeline);
    }

    private long createGraphicsPipeline(PipelineState state) {
        try (MemoryStack stack = stackPush()) {
            ByteBuffer entryPoint = stack.UTF8("main");

            VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack);

            VkPipelineShaderStageCreateInfo vertShaderStageInfo = shaderStages.get(0);

            vertShaderStageInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
            vertShaderStageInfo.stage(VK_SHADER_STAGE_VERTEX_BIT);
            vertShaderStageInfo.module(vertShaderModule);
            vertShaderStageInfo.pName(entryPoint);

            VkPipelineShaderStageCreateInfo fragShaderStageInfo = shaderStages.get(1);

            fragShaderStageInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
            fragShaderStageInfo.stage(VK_SHADER_STAGE_FRAGMENT_BIT);
            fragShaderStageInfo.module(fragShaderModule);
            fragShaderStageInfo.pName(entryPoint);

            // ===> VERTEX STAGE <===

            VkPipelineVertexInputStateCreateInfo vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack);
            vertexInputInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);

            if (vertexInputDescription != null) {
                vertexInputInfo.pVertexBindingDescriptions(vertexInputDescription.bindingDescriptions);
                vertexInputInfo.pVertexAttributeDescriptions(vertexInputDescription.attributeDescriptions);
            }

            // ===> ASSEMBLY STAGE <===

            final int topology = PipelineState.AssemblyRasterState.decodeTopology(state.assemblyRasterState);

            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack);
            inputAssembly.sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO);
            inputAssembly.topology(topology);
            inputAssembly.primitiveRestartEnable(false);

            // ===> VIEWPORT & SCISSOR

            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack);
            viewportState.sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO);

            viewportState.viewportCount(1);
            viewportState.scissorCount(1);

            // ===> RASTERIZATION STAGE <===

            final int polygonMode = PipelineState.AssemblyRasterState.decodePolygonMode(state.assemblyRasterState);
            final int cullMode = PipelineState.AssemblyRasterState.decodeCullMode(state.assemblyRasterState);

            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack);
            rasterizer.sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO);
            rasterizer.depthClampEnable(false);
            rasterizer.rasterizerDiscardEnable(false);
            rasterizer.polygonMode(polygonMode);
            rasterizer.lineWidth(1.0f);
            rasterizer.cullMode(cullMode);
            rasterizer.frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);
            rasterizer.depthBiasEnable(true);

            // ===> MULTISAMPLING <===

            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack);
            multisampling.sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO);
            multisampling.sampleShadingEnable(false);
            multisampling.rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

            // ===> DEPTH TEST <===

            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack);
            depthStencil.sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO);
            depthStencil.depthTestEnable(PipelineState.DepthState.depthTest(state.depthState_i));
            depthStencil.depthWriteEnable(PipelineState.DepthState.depthMask(state.depthState_i));
            depthStencil.depthCompareOp(PipelineState.DepthState.decodeDepthFun(state.depthState_i));
            depthStencil.depthBoundsTestEnable(false);
            depthStencil.minDepthBounds(0.0f); // Optional
            depthStencil.maxDepthBounds(1.0f); // Optional
            depthStencil.stencilTestEnable(false);

            // ===> COLOR BLENDING <===

            Framebuffer framebuffer = null;
            if (state.renderPass != null) {
                framebuffer = state.renderPass.getFramebuffer();
            } else if (Renderer.getInstance().getMainPass() != null) {
                framebuffer = Renderer.getInstance().getMainPass().getMainFramebuffer();
            }

            int[] colorFormats;
            if (this.colorAttachmentFormats != null && this.colorAttachmentFormats.length > 0) {
                colorFormats = this.colorAttachmentFormats;
            } else if (framebuffer != null) {
                colorFormats = new int[]{ framebuffer.getFormat() };
            } else {
                colorFormats = new int[]{ Framebuffer.DEFAULT_FORMAT };
            }

            int depthFormat;
            if (this.depthAttachmentFormat != -1) {
                depthFormat = this.depthAttachmentFormat;
            } else if (framebuffer != null) {
                depthFormat = framebuffer.getDepthFormat();
            } else {
                depthFormat = 0;
            }

            int numAttachments = colorFormats.length;
            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachments = VkPipelineColorBlendAttachmentState.calloc(numAttachments, stack);
            for (int a = 0; a < numAttachments; ++a) {
                VkPipelineColorBlendAttachmentState colorBlendAttachment = colorBlendAttachments.get(a);
                colorBlendAttachment.colorWriteMask(state.colorMask_i);

                boolean blendEnabled = PipelineState.BlendState.enable(state.blendState_i)
                        && (colorBlendDisabled == null || a >= colorBlendDisabled.length || !colorBlendDisabled[a]);
                if (blendEnabled) {
                    colorBlendAttachment.blendEnable(true);
                    colorBlendAttachment.srcColorBlendFactor(PipelineState.BlendState.getSrcRgbFactor(state.blendState_i));
                    colorBlendAttachment.dstColorBlendFactor(PipelineState.BlendState.getDstRgbFactor(state.blendState_i));
                    colorBlendAttachment.colorBlendOp(PipelineState.BlendState.blendOp(state.blendState_i));
                    colorBlendAttachment.srcAlphaBlendFactor(PipelineState.BlendState.getSrcAlphaFactor(state.blendState_i));
                    colorBlendAttachment.dstAlphaBlendFactor(PipelineState.BlendState.getDstAlphaFactor(state.blendState_i));
                    colorBlendAttachment.alphaBlendOp(PipelineState.BlendState.blendOp(state.blendState_i));
                }
                else {
                    colorBlendAttachment.blendEnable(false);
                }
            }

            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack);
            colorBlending.sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO);
            colorBlending.logicOpEnable(PipelineState.LogicOpState.enable(state.logicOp_i));
            colorBlending.logicOp(PipelineState.LogicOpState.decodeFun(state.logicOp_i));
            colorBlending.pAttachments(colorBlendAttachments);
            colorBlending.blendConstants(stack.floats(0.0f, 0.0f, 0.0f, 0.0f));

            // ===> DYNAMIC STATES <===

            VkPipelineDynamicStateCreateInfo dynamicStates = VkPipelineDynamicStateCreateInfo.calloc(stack);
            dynamicStates.sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO);

            if (topology == VK_PRIMITIVE_TOPOLOGY_LINE_LIST || polygonMode == VK_POLYGON_MODE_LINE) {
                dynamicStates.pDynamicStates(
                        stack.ints(VK_DYNAMIC_STATE_DEPTH_BIAS, VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR,
                                   VK_DYNAMIC_STATE_LINE_WIDTH));
            }
            else {
                dynamicStates.pDynamicStates(
                        stack.ints(VK_DYNAMIC_STATE_DEPTH_BIAS, VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));
            }

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            pipelineInfo.sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO);
            pipelineInfo.pStages(shaderStages);
            pipelineInfo.pVertexInputState(vertexInputInfo);
            pipelineInfo.pInputAssemblyState(inputAssembly);
            pipelineInfo.pViewportState(viewportState);
            pipelineInfo.pRasterizationState(rasterizer);
            pipelineInfo.pMultisampleState(multisampling);
            pipelineInfo.pDepthStencilState(depthStencil);
            pipelineInfo.pColorBlendState(colorBlending);
            pipelineInfo.pDynamicState(dynamicStates);
            pipelineInfo.layout(pipelineLayout);
            pipelineInfo.basePipelineHandle(VK_NULL_HANDLE);
            pipelineInfo.basePipelineIndex(-1);

            if (!Vulkan.DYNAMIC_RENDERING) {
                pipelineInfo.renderPass(state.renderPass.getId());
                pipelineInfo.subpass(0);
            }
            else {
                //dyn-rendering
                VkPipelineRenderingCreateInfoKHR renderingInfo = VkPipelineRenderingCreateInfoKHR.calloc(stack);
                renderingInfo.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR);

                renderingInfo.pColorAttachmentFormats(stack.ints(colorFormats));
                renderingInfo.depthAttachmentFormat(depthFormat);
                pipelineInfo.pNext(renderingInfo);
            }

            LongBuffer pGraphicsPipeline = stack.mallocLong(1);

            Vulkan.checkResult(vkCreateGraphicsPipelines(DeviceManager.vkDevice, PIPELINE_CACHE, pipelineInfo, null, pGraphicsPipeline),
                               "Failed to create graphics pipeline " + this.name);

            return pGraphicsPipeline.get(0);
        }
    }

    private void createShaderModules(Builder builder) {
        ByteBuffer vsSpirv;
        if (builder.shadersSpirv.containsKey(SpirvCompiler.ShaderKind.VERTEX_SHADER)) {
            vsSpirv = builder.shadersSpirv.get(SpirvCompiler.ShaderKind.VERTEX_SHADER);
        }
        else {
            String vsh = builder.shadersSrc.get(SpirvCompiler.ShaderKind.VERTEX_SHADER);
            SpirvCompiler.SPIRV vertShaderSPIRV = SpirvCompiler.compileShader(String.format("%s.vsh", name), vsh, SpirvCompiler.ShaderKind.VERTEX_SHADER);
            vsSpirv = vertShaderSPIRV.bytecode();
        }

        ByteBuffer fsSpirv;
        if (builder.shadersSpirv.containsKey(SpirvCompiler.ShaderKind.FRAGMENT_SHADER)) {
            fsSpirv = builder.shadersSpirv.get(SpirvCompiler.ShaderKind.FRAGMENT_SHADER);
        }
        else  {
            String fsh = builder.shadersSrc.get(SpirvCompiler.ShaderKind.FRAGMENT_SHADER);
            SpirvCompiler.SPIRV fragShaderSPIRV = SpirvCompiler.compileShader(String.format("%s.fsh", name), fsh, SpirvCompiler.ShaderKind.FRAGMENT_SHADER);
            fsSpirv = fragShaderSPIRV.bytecode();
        }


        this.vertShaderModule = createShaderModule(vsSpirv);
        this.fragShaderModule = createShaderModule(fsSpirv);
    }

    public void cleanUp() {
        vkDestroyShaderModule(DeviceManager.vkDevice, vertShaderModule, null);
        vkDestroyShaderModule(DeviceManager.vkDevice, fragShaderModule, null);

        vertexInputDescription.cleanUp();

        destroyDescriptorSets();

        graphicsPipelines.forEach((state, pipeline) -> {
            vkDestroyPipeline(DeviceManager.vkDevice, pipeline, null);
        });
        graphicsPipelines.clear();

        vkDestroyDescriptorSetLayout(DeviceManager.vkDevice, descriptorSetLayout, null);
        vkDestroyPipelineLayout(DeviceManager.vkDevice, pipelineLayout, null);

        PIPELINES.remove(this);
        Renderer.getInstance().removeUsedPipeline(this);
    }

    static class VertexInputDescription {
        final VkVertexInputAttributeDescription.Buffer attributeDescriptions;
        final VkVertexInputBindingDescription.Buffer bindingDescriptions;

        VertexInputDescription(VertexFormat vertexFormat) {
            if (vertexFormat != DefaultVertexFormat.EMPTY) {
                this.bindingDescriptions = getBindingDescription(vertexFormat);
                this.attributeDescriptions = getAttributeDescriptions(vertexFormat);
            }
            else {
                this.bindingDescriptions = null;
                this.attributeDescriptions = null;
            }
        }

        void cleanUp() {
            if (this.bindingDescriptions != null) {
                MemoryUtil.memFree(this.bindingDescriptions);
                MemoryUtil.memFree(this.attributeDescriptions);
            }
        }
    }

    private static VkVertexInputBindingDescription.Buffer getBindingDescription(VertexFormat vertexFormat) {
        VkVertexInputBindingDescription.Buffer bindingDescription = VkVertexInputBindingDescription.calloc(1);

        bindingDescription.binding(0);
        bindingDescription.stride(vertexFormat.getVertexSize());
        bindingDescription.inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

        return bindingDescription;
    }

    private static VkVertexInputAttributeDescription.Buffer getAttributeDescriptions(VertexFormat vertexFormat) {
        List<VertexFormatElement> elements = vertexFormat.getElements();

        int size = elements.size();

        VkVertexInputAttributeDescription.Buffer attributeDescriptions = VkVertexInputAttributeDescription.calloc(size);

        int offset = 0;

        for (int i = 0; i < size; ++i) {
            VkVertexInputAttributeDescription posDescription = attributeDescriptions.get(i);
            posDescription.binding(0);
            // Pack terrain shaders use the legacy Iris attribute locations:
            // mc_Entity/midCoord (0/1), Position/Normal/Color/UV0/UV2
            // (4/5/6/7/8). The metadata attributes are currently constants
            // emitted by ShaderProcessor, so only the five real attributes
            // are described here.
            int location = vertexFormat == CustomVertexFormat.TERRAIN
                    ? switch (i) { case 0 -> 4; case 1 -> 0; case 2 -> 1; case 3 -> 6; case 4 -> 7; case 5 -> 8; case 6 -> 5; default -> i; }
                    : (vertexFormat == CustomVertexFormat.QUAD
                    ? switch (i) { case 0 -> 4; case 1 -> 7; default -> i; }
                    : i);
            posDescription.location(location);

            VertexFormatElement formatElement = elements.get(i);
            int id = formatElement.id();
            VertexFormatElement.Type type = formatElement.type();
            int elementCount = formatElement.count();

            switch (id) {
                case 0 -> { // POSITION
                    switch (type) {
                        case FLOAT -> {
                            posDescription.offset(offset);
                            if (elementCount == 4) {
                                posDescription.format(VK_FORMAT_R32G32B32A32_SFLOAT);
                                offset += 16;
                            } else {
                                posDescription.format(VK_FORMAT_R32G32B32_SFLOAT);
                                offset += 12;
                            }
                        }
                        case SHORT -> {
                            posDescription.format(VK_FORMAT_R16G16B16A16_SINT);
                            posDescription.offset(offset);

                            offset += 8;
                        }
                        case BYTE -> {
                            posDescription.format(VK_FORMAT_R8G8B8A8_SINT);
                            posDescription.offset(offset);

                            offset += 4;
                        }
                    }

                }

                case 1 -> { // COLOR
                    switch (type) {
                        case UBYTE -> {
                            posDescription.format(VK_FORMAT_R8G8B8A8_UNORM);
                            posDescription.offset(offset);

                            offset += 4;
                        }
                        case UINT -> {
                            posDescription.format(VK_FORMAT_R32_UINT);
                            posDescription.offset(offset);

                            offset += 4;
                        }
                    }
                }

                case 2, 3, 4 -> { // UV / UV0 / UV1 / UV2
                    switch (type) {
                        case FLOAT -> {
                            posDescription.offset(offset);
                            if (elementCount == 4) {
                                posDescription.format(VK_FORMAT_R32G32B32A32_SFLOAT);
                                offset += 16;
                            } else {
                                posDescription.format(VK_FORMAT_R32G32_SFLOAT);
                                offset += 8;
                            }
                        }
                        case SHORT -> {
                            posDescription.format(VK_FORMAT_R16G16_SINT);
                            posDescription.offset(offset);

                            offset += 4;
                        }
                        case USHORT -> {
                            posDescription.format(VK_FORMAT_R16G16_UINT);
                            posDescription.offset(offset);

                            offset += 4;
                        }
                        case UINT -> {
                            posDescription.format(VK_FORMAT_R32_UINT);
                            posDescription.offset(offset);

                            offset += 4;
                        }
                        case BYTE -> {
                            posDescription.format(VK_FORMAT_R8G8B8A8_SNORM);
                            posDescription.offset(offset);

                            offset += 4;
                        }
                    }
                }

                case 5 -> { // NORMAL
                    posDescription.format(VK_FORMAT_R8G8B8A8_SNORM);
                    posDescription.offset(offset);

                    offset += 4;
                }

                default -> { // GENERIC / OTHER (or custom mod attributes such as Distant Horizons)
                    boolean norm = formatElement.normalized();
                    int format = switch (type) {
                        case FLOAT -> switch (elementCount) {
                            case 1 -> VK_FORMAT_R32_SFLOAT;
                            case 2 -> VK_FORMAT_R32G32_SFLOAT;
                            case 3 -> VK_FORMAT_R32G32B32_SFLOAT;
                            case 4 -> VK_FORMAT_R32G32B32A32_SFLOAT;
                            default -> 0;
                        };
                        case UBYTE -> norm ? switch (elementCount) {
                            case 1 -> VK_FORMAT_R8_UNORM;
                            case 2 -> VK_FORMAT_R8G8_UNORM;
                            case 3 -> VK_FORMAT_R8G8B8_UNORM;
                            case 4 -> VK_FORMAT_R8G8B8A8_UNORM;
                            default -> 0;
                        } : switch (elementCount) {
                            case 1 -> VK_FORMAT_R8_UINT;
                            case 2 -> VK_FORMAT_R8G8_UINT;
                            case 3 -> VK_FORMAT_R8G8B8_UINT;
                            case 4 -> VK_FORMAT_R8G8B8A8_UINT;
                            default -> 0;
                        };
                        case BYTE -> norm ? switch (elementCount) {
                            case 1 -> VK_FORMAT_R8_SNORM;
                            case 2 -> VK_FORMAT_R8G8_SNORM;
                            case 3 -> VK_FORMAT_R8G8B8_SNORM;
                            case 4 -> VK_FORMAT_R8G8B8A8_SNORM;
                            default -> 0;
                        } : switch (elementCount) {
                            case 1 -> VK_FORMAT_R8_SINT;
                            case 2 -> VK_FORMAT_R8G8_SINT;
                            case 3 -> VK_FORMAT_R8G8B8_SINT;
                            case 4 -> VK_FORMAT_R8G8B8A8_SINT;
                            default -> 0;
                        };
                        case USHORT -> norm ? switch (elementCount) {
                            case 1 -> VK_FORMAT_R16_UNORM;
                            case 2 -> VK_FORMAT_R16G16_UNORM;
                            case 3 -> VK_FORMAT_R16G16B16_UNORM;
                            case 4 -> VK_FORMAT_R16G16B16A16_UNORM;
                            default -> 0;
                        } : switch (elementCount) {
                            case 1 -> VK_FORMAT_R16_UINT;
                            case 2 -> VK_FORMAT_R16G16_UINT;
                            case 3 -> VK_FORMAT_R16G16B16_UINT;
                            case 4 -> VK_FORMAT_R16G16B16A16_UINT;
                            default -> 0;
                        };
                        case SHORT -> norm ? switch (elementCount) {
                            case 1 -> VK_FORMAT_R16_SNORM;
                            case 2 -> VK_FORMAT_R16G16_SNORM;
                            case 3 -> VK_FORMAT_R16G16B16_SNORM;
                            case 4 -> VK_FORMAT_R16G16B16A16_SNORM;
                            default -> 0;
                        } : switch (elementCount) {
                            case 1 -> VK_FORMAT_R16_SINT;
                            case 2 -> VK_FORMAT_R16G16_SINT;
                            case 3 -> VK_FORMAT_R16G16B16_SINT;
                            case 4 -> VK_FORMAT_R16G16B16A16_SINT;
                            default -> 0;
                        };
                        case UINT -> switch (elementCount) {
                            case 1 -> VK_FORMAT_R32_UINT;
                            case 2 -> VK_FORMAT_R32G32_UINT;
                            case 3 -> VK_FORMAT_R32G32B32_UINT;
                            case 4 -> VK_FORMAT_R32G32B32A32_UINT;
                            default -> 0;
                        };
                        case INT -> switch (elementCount) {
                            case 1 -> VK_FORMAT_R32_SINT;
                            case 2 -> VK_FORMAT_R32G32_SINT;
                            case 3 -> VK_FORMAT_R32G32B32_SINT;
                            case 4 -> VK_FORMAT_R32G32B32A32_SINT;
                            default -> 0;
                        };
                    };

                    if (format == 0) {
                        throw new RuntimeException(String.format("Unknown format element id: %s type: %s count: %s normalized: %s", id, type, elementCount, norm));
                    }
                    posDescription.format(format);
                    offset += formatElement.byteSize();
                }
            }

            if (posDescription.format() == 0) {
                throw new IllegalStateException(String.format("Vertex format element VkFormat unset for id: %s type: %s in format %s", id, type, vertexFormat));
            }

            posDescription.offset(((VertexFormatMixed) (vertexFormat)).getOffset(i));
        }

        return attributeDescriptions.rewind();
    }
}
