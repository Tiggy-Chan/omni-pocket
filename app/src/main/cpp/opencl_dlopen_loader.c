#define CL_TARGET_OPENCL_VERSION 300
#define CL_USE_DEPRECATED_OPENCL_1_2_APIS
#include <CL/cl.h>

#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stddef.h>

// Lightweight runtime OpenCL loader for Android.
// Avoids DT_NEEDED: libOpenCL.so so libomni_decision.so loads safely on every Android device
// (including emulators and phones without vendor OpenCL), while using vendor libOpenCL.so on
// Adreno / Mali / Xclipse devices when present.

#define OCL_TAG "OmniOpenCL"

static void* g_opencl_handle = NULL;
static pthread_once_t g_opencl_once = PTHREAD_ONCE_INIT;

static void init_opencl_handle(void) {
    static const char* const kCandidates[] = {
        "libOpenCL.so",
        "/vendor/lib64/libOpenCL.so",
        "/system/vendor/lib64/libOpenCL.so",
        "libOpenCL-pixel.so",
        "/vendor/lib64/libOpenCL-pixel.so",
    };
    for (size_t i = 0; i < sizeof(kCandidates) / sizeof(kCandidates[0]); ++i) {
        void* handle = dlopen(kCandidates[i], RTLD_LAZY | RTLD_LOCAL);
        if (handle != NULL) {
            g_opencl_handle = handle;
            __android_log_print(ANDROID_LOG_INFO, OCL_TAG, "Loaded OpenCL runtime: %s", kCandidates[i]);
            return;
        }
    }
    __android_log_print(ANDROID_LOG_INFO, OCL_TAG, "No vendor OpenCL runtime found; GGUF will run on CPU");
}

static void* resolve_cl(const char* name) {
    pthread_once(&g_opencl_once, init_opencl_handle);
    if (g_opencl_handle == NULL) return NULL;
    return dlsym(g_opencl_handle, name);
}

#define LOAD_CL_FN(ret, name, args)                                        \
    typedef ret (CL_API_CALL *pfn_##name) args;                            \
    static pfn_##name fn = NULL;                                           \
    if (fn == NULL) fn = (pfn_##name)resolve_cl(#name);

CL_API_ENTRY cl_int CL_API_CALL clGetPlatformIDs(
    cl_uint num_entries, cl_platform_id* platforms, cl_uint* num_platforms) {
    LOAD_CL_FN(cl_int, clGetPlatformIDs, (cl_uint, cl_platform_id*, cl_uint*));
    if (fn == NULL) {
        if (num_platforms != NULL) *num_platforms = 0;
        return -1001; // CL_PLATFORM_NOT_FOUND_KHR
    }
    return fn(num_entries, platforms, num_platforms);
}

CL_API_ENTRY cl_int CL_API_CALL clGetPlatformInfo(
    cl_platform_id platform, cl_platform_info param_name, size_t param_value_size,
    void* param_value, size_t* param_value_size_ret) {
    LOAD_CL_FN(cl_int, clGetPlatformInfo, (cl_platform_id, cl_platform_info, size_t, void*, size_t*));
    return fn ? fn(platform, param_name, param_value_size, param_value, param_value_size_ret) : CL_INVALID_PLATFORM;
}

CL_API_ENTRY cl_int CL_API_CALL clGetDeviceIDs(
    cl_platform_id platform, cl_device_type device_type, cl_uint num_entries,
    cl_device_id* devices, cl_uint* num_devices) {
    LOAD_CL_FN(cl_int, clGetDeviceIDs, (cl_platform_id, cl_device_type, cl_uint, cl_device_id*, cl_uint*));
    if (fn == NULL) {
        if (num_devices != NULL) *num_devices = 0;
        return CL_DEVICE_NOT_FOUND;
    }
    return fn(platform, device_type, num_entries, devices, num_devices);
}

CL_API_ENTRY cl_int CL_API_CALL clGetDeviceInfo(
    cl_device_id device, cl_device_info param_name, size_t param_value_size,
    void* param_value, size_t* param_value_size_ret) {
    LOAD_CL_FN(cl_int, clGetDeviceInfo, (cl_device_id, cl_device_info, size_t, void*, size_t*));
    return fn ? fn(device, param_name, param_value_size, param_value, param_value_size_ret) : CL_INVALID_DEVICE;
}

CL_API_ENTRY cl_context CL_API_CALL clCreateContext(
    const cl_context_properties* properties, cl_uint num_devices, const cl_device_id* devices,
    void (CL_CALLBACK* pfn_notify)(const char*, const void*, size_t, void*),
    void* user_data, cl_int* errcode_ret) {
    LOAD_CL_FN(cl_context, clCreateContext,
               (const cl_context_properties*, cl_uint, const cl_device_id*,
                void (CL_CALLBACK*)(const char*, const void*, size_t, void*), void*, cl_int*));
    if (fn == NULL) {
        if (errcode_ret) *errcode_ret = CL_DEVICE_NOT_AVAILABLE;
        return NULL;
    }
    return fn(properties, num_devices, devices, pfn_notify, user_data, errcode_ret);
}

CL_API_ENTRY cl_int CL_API_CALL clReleaseContext(cl_context context) {
    LOAD_CL_FN(cl_int, clReleaseContext, (cl_context));
    return fn ? fn(context) : CL_INVALID_CONTEXT;
}

CL_API_ENTRY cl_command_queue CL_API_CALL clCreateCommandQueue(
    cl_context context, cl_device_id device, cl_command_queue_properties properties, cl_int* errcode_ret) {
    LOAD_CL_FN(cl_command_queue, clCreateCommandQueue,
               (cl_context, cl_device_id, cl_command_queue_properties, cl_int*));
    if (fn == NULL) {
        if (errcode_ret) *errcode_ret = CL_DEVICE_NOT_AVAILABLE;
        return NULL;
    }
    return fn(context, device, properties, errcode_ret);
}

CL_API_ENTRY cl_command_queue CL_API_CALL clCreateCommandQueueWithProperties(
    cl_context context, cl_device_id device, const cl_queue_properties* properties, cl_int* errcode_ret) {
    LOAD_CL_FN(cl_command_queue, clCreateCommandQueueWithProperties,
               (cl_context, cl_device_id, const cl_queue_properties*, cl_int*));
    if (fn != NULL) return fn(context, device, properties, errcode_ret);
    return clCreateCommandQueue(context, device, 0, errcode_ret);
}

CL_API_ENTRY cl_int CL_API_CALL clReleaseCommandQueue(cl_command_queue command_queue) {
    LOAD_CL_FN(cl_int, clReleaseCommandQueue, (cl_command_queue));
    return fn ? fn(command_queue) : CL_INVALID_COMMAND_QUEUE;
}

CL_API_ENTRY cl_mem CL_API_CALL clCreateBuffer(
    cl_context context, cl_mem_flags flags, size_t size, void* host_ptr, cl_int* errcode_ret) {
    LOAD_CL_FN(cl_mem, clCreateBuffer, (cl_context, cl_mem_flags, size_t, void*, cl_int*));
    if (fn == NULL) {
        if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION;
        return NULL;
    }
    return fn(context, flags, size, host_ptr, errcode_ret);
}

CL_API_ENTRY cl_mem CL_API_CALL clCreateBufferWithProperties(
    cl_context context, const cl_mem_properties* properties, cl_mem_flags flags,
    size_t size, void* host_ptr, cl_int* errcode_ret) {
    LOAD_CL_FN(cl_mem, clCreateBufferWithProperties,
               (cl_context, const cl_mem_properties*, cl_mem_flags, size_t, void*, cl_int*));
    if (fn != NULL) return fn(context, properties, flags, size, host_ptr, errcode_ret);
    if (properties == NULL || properties[0] == 0) {
        return clCreateBuffer(context, flags, size, host_ptr, errcode_ret);
    }
    if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION;
    return NULL;
}

CL_API_ENTRY cl_mem CL_API_CALL clCreateSubBuffer(
    cl_mem buffer, cl_mem_flags flags, cl_buffer_create_type buffer_create_type,
    const void* buffer_create_info, cl_int* errcode_ret) {
    LOAD_CL_FN(cl_mem, clCreateSubBuffer,
               (cl_mem, cl_mem_flags, cl_buffer_create_type, const void*, cl_int*));
    if (fn == NULL) {
        if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION;
        return NULL;
    }
    return fn(buffer, flags, buffer_create_type, buffer_create_info, errcode_ret);
}

CL_API_ENTRY cl_mem CL_API_CALL clCreateImage(
    cl_context context, cl_mem_flags flags, const cl_image_format* image_format,
    const cl_image_desc* image_desc, void* host_ptr, cl_int* errcode_ret) {
    LOAD_CL_FN(cl_mem, clCreateImage,
               (cl_context, cl_mem_flags, const cl_image_format*, const cl_image_desc*, void*, cl_int*));
    if (fn == NULL) {
        if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION;
        return NULL;
    }
    return fn(context, flags, image_format, image_desc, host_ptr, errcode_ret);
}

CL_API_ENTRY cl_int CL_API_CALL clReleaseMemObject(cl_mem memobj) {
    LOAD_CL_FN(cl_int, clReleaseMemObject, (cl_mem));
    return fn ? fn(memobj) : CL_INVALID_MEM_OBJECT;
}

CL_API_ENTRY cl_program CL_API_CALL clCreateProgramWithSource(
    cl_context context, cl_uint count, const char** strings, const size_t* lengths, cl_int* errcode_ret) {
    LOAD_CL_FN(cl_program, clCreateProgramWithSource,
               (cl_context, cl_uint, const char**, const size_t*, cl_int*));
    if (fn == NULL) {
        if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION;
        return NULL;
    }
    return fn(context, count, strings, lengths, errcode_ret);
}

CL_API_ENTRY cl_program CL_API_CALL clCreateProgramWithBinary(
    cl_context context, cl_uint num_devices, const cl_device_id* device_list,
    const size_t* lengths, const unsigned char** binaries, cl_int* binary_status, cl_int* errcode_ret) {
    LOAD_CL_FN(cl_program, clCreateProgramWithBinary,
               (cl_context, cl_uint, const cl_device_id*, const size_t*, const unsigned char**, cl_int*, cl_int*));
    if (fn == NULL) {
        if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION;
        return NULL;
    }
    return fn(context, num_devices, device_list, lengths, binaries, binary_status, errcode_ret);
}

CL_API_ENTRY cl_int CL_API_CALL clBuildProgram(
    cl_program program, cl_uint num_devices, const cl_device_id* device_list, const char* options,
    void (CL_CALLBACK* pfn_notify)(cl_program, void*), void* user_data) {
    LOAD_CL_FN(cl_int, clBuildProgram,
               (cl_program, cl_uint, const cl_device_id*, const char*,
                void (CL_CALLBACK*)(cl_program, void*), void*));
    return fn ? fn(program, num_devices, device_list, options, pfn_notify, user_data) : CL_INVALID_PROGRAM;
}

CL_API_ENTRY cl_int CL_API_CALL clGetProgramInfo(
    cl_program program, cl_program_info param_name, size_t param_value_size,
    void* param_value, size_t* param_value_size_ret) {
    LOAD_CL_FN(cl_int, clGetProgramInfo, (cl_program, cl_program_info, size_t, void*, size_t*));
    return fn ? fn(program, param_name, param_value_size, param_value, param_value_size_ret) : CL_INVALID_PROGRAM;
}

CL_API_ENTRY cl_int CL_API_CALL clGetProgramBuildInfo(
    cl_program program, cl_device_id device, cl_program_build_info param_name,
    size_t param_value_size, void* param_value, size_t* param_value_size_ret) {
    LOAD_CL_FN(cl_int, clGetProgramBuildInfo,
               (cl_program, cl_device_id, cl_program_build_info, size_t, void*, size_t*));
    return fn ? fn(program, device, param_name, param_value_size, param_value, param_value_size_ret) : CL_INVALID_PROGRAM;
}

CL_API_ENTRY cl_int CL_API_CALL clReleaseProgram(cl_program program) {
    LOAD_CL_FN(cl_int, clReleaseProgram, (cl_program));
    return fn ? fn(program) : CL_INVALID_PROGRAM;
}

CL_API_ENTRY cl_kernel CL_API_CALL clCreateKernel(
    cl_program program, const char* kernel_name, cl_int* errcode_ret) {
    LOAD_CL_FN(cl_kernel, clCreateKernel, (cl_program, const char*, cl_int*));
    if (fn == NULL) {
        if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION;
        return NULL;
    }
    return fn(program, kernel_name, errcode_ret);
}

CL_API_ENTRY cl_int CL_API_CALL clSetKernelArg(
    cl_kernel kernel, cl_uint arg_index, size_t arg_size, const void* arg_value) {
    LOAD_CL_FN(cl_int, clSetKernelArg, (cl_kernel, cl_uint, size_t, const void*));
    return fn ? fn(kernel, arg_index, arg_size, arg_value) : CL_INVALID_KERNEL;
}

CL_API_ENTRY cl_int CL_API_CALL clGetKernelWorkGroupInfo(
    cl_kernel kernel, cl_device_id device, cl_kernel_work_group_info param_name,
    size_t param_value_size, void* param_value, size_t* param_value_size_ret) {
    LOAD_CL_FN(cl_int, clGetKernelWorkGroupInfo,
               (cl_kernel, cl_device_id, cl_kernel_work_group_info, size_t, void*, size_t*));
    return fn ? fn(kernel, device, param_name, param_value_size, param_value, param_value_size_ret) : CL_INVALID_KERNEL;
}

CL_API_ENTRY cl_int CL_API_CALL clGetKernelSubGroupInfo(
    cl_kernel kernel, cl_device_id device, cl_kernel_sub_group_info param_name,
    size_t input_value_size, const void* input_value, size_t param_value_size,
    void* param_value, size_t* param_value_size_ret) {
    LOAD_CL_FN(cl_int, clGetKernelSubGroupInfo,
               (cl_kernel, cl_device_id, cl_kernel_sub_group_info, size_t, const void*, size_t, void*, size_t*));
    return fn ? fn(kernel, device, param_name, input_value_size, input_value,
                   param_value_size, param_value, param_value_size_ret) : CL_INVALID_OPERATION;
}

CL_API_ENTRY cl_int CL_API_CALL clReleaseKernel(cl_kernel kernel) {
    LOAD_CL_FN(cl_int, clReleaseKernel, (cl_kernel));
    return fn ? fn(kernel) : CL_INVALID_KERNEL;
}

CL_API_ENTRY cl_int CL_API_CALL clEnqueueReadBuffer(
    cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_read, size_t offset, size_t size,
    void* ptr, cl_uint num_events_in_wait_list, const cl_event* event_wait_list, cl_event* event) {
    LOAD_CL_FN(cl_int, clEnqueueReadBuffer,
               (cl_command_queue, cl_mem, cl_bool, size_t, size_t, void*, cl_uint, const cl_event*, cl_event*));
    return fn ? fn(command_queue, buffer, blocking_read, offset, size, ptr,
                   num_events_in_wait_list, event_wait_list, event) : CL_INVALID_COMMAND_QUEUE;
}

CL_API_ENTRY cl_int CL_API_CALL clEnqueueWriteBuffer(
    cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_write, size_t offset, size_t size,
    const void* ptr, cl_uint num_events_in_wait_list, const cl_event* event_wait_list, cl_event* event) {
    LOAD_CL_FN(cl_int, clEnqueueWriteBuffer,
               (cl_command_queue, cl_mem, cl_bool, size_t, size_t, const void*, cl_uint, const cl_event*, cl_event*));
    return fn ? fn(command_queue, buffer, blocking_write, offset, size, ptr,
                   num_events_in_wait_list, event_wait_list, event) : CL_INVALID_COMMAND_QUEUE;
}

CL_API_ENTRY cl_int CL_API_CALL clEnqueueCopyBuffer(
    cl_command_queue command_queue, cl_mem src_buffer, cl_mem dst_buffer, size_t src_offset, size_t dst_offset,
    size_t size, cl_uint num_events_in_wait_list, const cl_event* event_wait_list, cl_event* event) {
    LOAD_CL_FN(cl_int, clEnqueueCopyBuffer,
               (cl_command_queue, cl_mem, cl_mem, size_t, size_t, size_t, cl_uint, const cl_event*, cl_event*));
    return fn ? fn(command_queue, src_buffer, dst_buffer, src_offset, dst_offset, size,
                   num_events_in_wait_list, event_wait_list, event) : CL_INVALID_COMMAND_QUEUE;
}

CL_API_ENTRY cl_int CL_API_CALL clEnqueueFillBuffer(
    cl_command_queue command_queue, cl_mem buffer, const void* pattern, size_t pattern_size, size_t offset,
    size_t size, cl_uint num_events_in_wait_list, const cl_event* event_wait_list, cl_event* event) {
    LOAD_CL_FN(cl_int, clEnqueueFillBuffer,
               (cl_command_queue, cl_mem, const void*, size_t, size_t, size_t, cl_uint, const cl_event*, cl_event*));
    return fn ? fn(command_queue, buffer, pattern, pattern_size, offset, size,
                   num_events_in_wait_list, event_wait_list, event) : CL_INVALID_COMMAND_QUEUE;
}

CL_API_ENTRY void* CL_API_CALL clEnqueueMapBuffer(
    cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_map, cl_map_flags map_flags,
    size_t offset, size_t size, cl_uint num_events_in_wait_list, const cl_event* event_wait_list,
    cl_event* event, cl_int* errcode_ret) {
    LOAD_CL_FN(void*, clEnqueueMapBuffer,
               (cl_command_queue, cl_mem, cl_bool, cl_map_flags, size_t, size_t, cl_uint, const cl_event*, cl_event*, cl_int*));
    if (fn == NULL) {
        if (errcode_ret) *errcode_ret = CL_INVALID_COMMAND_QUEUE;
        return NULL;
    }
    return fn(command_queue, buffer, blocking_map, map_flags, offset, size,
              num_events_in_wait_list, event_wait_list, event, errcode_ret);
}

CL_API_ENTRY cl_int CL_API_CALL clEnqueueUnmapMemObject(
    cl_command_queue command_queue, cl_mem memobj, void* mapped_ptr,
    cl_uint num_events_in_wait_list, const cl_event* event_wait_list, cl_event* event) {
    LOAD_CL_FN(cl_int, clEnqueueUnmapMemObject,
               (cl_command_queue, cl_mem, void*, cl_uint, const cl_event*, cl_event*));
    return fn ? fn(command_queue, memobj, mapped_ptr, num_events_in_wait_list, event_wait_list, event)
              : CL_INVALID_COMMAND_QUEUE;
}

CL_API_ENTRY cl_int CL_API_CALL clEnqueueNDRangeKernel(
    cl_command_queue command_queue, cl_kernel kernel, cl_uint work_dim,
    const size_t* global_work_offset, const size_t* global_work_size, const size_t* local_work_size,
    cl_uint num_events_in_wait_list, const cl_event* event_wait_list, cl_event* event) {
    LOAD_CL_FN(cl_int, clEnqueueNDRangeKernel,
               (cl_command_queue, cl_kernel, cl_uint, const size_t*, const size_t*, const size_t*,
                cl_uint, const cl_event*, cl_event*));
    return fn ? fn(command_queue, kernel, work_dim, global_work_offset, global_work_size, local_work_size,
                   num_events_in_wait_list, event_wait_list, event) : CL_INVALID_COMMAND_QUEUE;
}

CL_API_ENTRY cl_int CL_API_CALL clEnqueueMarkerWithWaitList(
    cl_command_queue command_queue, cl_uint num_events_in_wait_list, const cl_event* event_wait_list, cl_event* event) {
    LOAD_CL_FN(cl_int, clEnqueueMarkerWithWaitList,
               (cl_command_queue, cl_uint, const cl_event*, cl_event*));
    return fn ? fn(command_queue, num_events_in_wait_list, event_wait_list, event) : CL_INVALID_COMMAND_QUEUE;
}

CL_API_ENTRY cl_int CL_API_CALL clEnqueueBarrierWithWaitList(
    cl_command_queue command_queue, cl_uint num_events_in_wait_list, const cl_event* event_wait_list, cl_event* event) {
    LOAD_CL_FN(cl_int, clEnqueueBarrierWithWaitList,
               (cl_command_queue, cl_uint, const cl_event*, cl_event*));
    return fn ? fn(command_queue, num_events_in_wait_list, event_wait_list, event) : CL_INVALID_COMMAND_QUEUE;
}

CL_API_ENTRY cl_int CL_API_CALL clWaitForEvents(cl_uint num_events, const cl_event* event_list) {
    LOAD_CL_FN(cl_int, clWaitForEvents, (cl_uint, const cl_event*));
    return fn ? fn(num_events, event_list) : CL_INVALID_EVENT;
}

CL_API_ENTRY cl_int CL_API_CALL clReleaseEvent(cl_event event) {
    LOAD_CL_FN(cl_int, clReleaseEvent, (cl_event));
    return fn ? fn(event) : CL_INVALID_EVENT;
}

CL_API_ENTRY cl_int CL_API_CALL clFlush(cl_command_queue command_queue) {
    LOAD_CL_FN(cl_int, clFlush, (cl_command_queue));
    return fn ? fn(command_queue) : CL_INVALID_COMMAND_QUEUE;
}

CL_API_ENTRY cl_int CL_API_CALL clFinish(cl_command_queue command_queue) {
    LOAD_CL_FN(cl_int, clFinish, (cl_command_queue));
    return fn ? fn(command_queue) : CL_INVALID_COMMAND_QUEUE;
}

CL_API_ENTRY cl_int CL_API_CALL clGetEventProfilingInfo(
    cl_event event, cl_profiling_info param_name, size_t param_value_size,
    void* param_value, size_t* param_value_size_ret) {
    LOAD_CL_FN(cl_int, clGetEventProfilingInfo, (cl_event, cl_profiling_info, size_t, void*, size_t*));
    return fn ? fn(event, param_name, param_value_size, param_value, param_value_size_ret) : CL_INVALID_EVENT;
}
