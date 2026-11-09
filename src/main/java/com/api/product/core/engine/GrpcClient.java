package com.api.product.core.engine;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;

import io.grpc.CallOptions;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;
import io.grpc.reflection.v1alpha.ServerReflectionRequest;
import io.grpc.reflection.v1alpha.ServerReflectionResponse;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;

/**
 * Dynamic gRPC client for unary calls. Message types are learned from the server itself through gRPC server reflection, so no
 * generated stubs or .proto files are needed. Requests and responses are JSON. The outcome is reduced to an HTTP-like status so the
 * normal judging rules apply (OK = 200, INVALID_ARGUMENT = 400, UNAUTHENTICATED = 401, PERMISSION_DENIED = 403, NOT_FOUND = 404 ...).
 */
public final class GrpcClient {

    public static final class Result {
        public int status;
        public int grpcCode = -1;
        public String grpcName = "";
        public String body = "";
        public String message = "";
        public String error;
        public long ms;
        /** The request could not be put on the wire (a typed protobuf client cannot send a wrong type or an unknown field). */
        public boolean unsendable;
    }

    public static final class Method {
        public String service;
        public String name;
        public String fullMethod;
        public boolean unary;
        public String sampleJson;
    }

    private static final Map<String, FileDescriptor> FILES = new ConcurrentHashMap<>();

    private GrpcClient() {}

    // ------------------------------------------------------------------ channel

    static ManagedChannel channel(String host, int port, boolean tls) throws Exception {
        NettyChannelBuilder b = NettyChannelBuilder.forAddress(host, port);
        if (tls) b.sslContext(GrpcSslContexts.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build());
        else b.usePlaintext();
        return b.build();
    }

    // ------------------------------------------------------------------ discovery

    /** Lists the unary methods of every service the server exposes (reflection and health services are left out). */
    public static List<Method> discover(String host, int port, boolean tls) throws Exception {
        ManagedChannel ch = channel(host, port, tls);
        try {
            List<String> services = reflect(ch, ServerReflectionRequest.newBuilder().setListServices("").build()).stream()
                    .filter(ServerReflectionResponse::hasListServicesResponse)
                    .flatMap(r -> r.getListServicesResponse().getServiceList().stream()).map(s -> s.getName()).collect(java.util.stream.Collectors.toList());
            if (services.isEmpty()) throw new IllegalArgumentException("The server answered but exposes no services through reflection.");
            List<Method> out = new ArrayList<>();
            for (String svc : services) {
                if (svc.startsWith("grpc.reflection.")) continue;
                Descriptors.ServiceDescriptor sd = serviceDescriptor(ch, svc);
                for (MethodDescriptor md : sd.getMethods()) {
                    Method m = new Method();
                    m.service = svc;
                    m.name = md.getName();
                    m.fullMethod = "/" + svc + "/" + md.getName();
                    m.unary = !md.isClientStreaming() && !md.isServerStreaming();
                    if (m.unary) m.sampleJson = JsonFormat.printer().includingDefaultValueFields().omittingInsignificantWhitespace().print(sample(md.getInputType(), 0, new java.util.HashSet<>()));
                    out.add(m);
                }
            }
            return out;
        } finally {
            ch.shutdownNow();
        }
    }

    private static List<ServerReflectionResponse> reflect(ManagedChannel ch, ServerReflectionRequest req) throws Exception {
        List<ServerReflectionResponse> got = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        StreamObserver<ServerReflectionRequest> in = ServerReflectionGrpc.newStub(ch).withDeadlineAfter(10, TimeUnit.SECONDS).serverReflectionInfo(new StreamObserver<ServerReflectionResponse>() {
            public void onNext(ServerReflectionResponse v) {
                got.add(v);
            }

            public void onError(Throwable t) {
                err.set(t);
                done.countDown();
            }

            public void onCompleted() {
                done.countDown();
            }
        });
        in.onNext(req);
        in.onCompleted();
        if (!done.await(12, TimeUnit.SECONDS)) throw new IllegalStateException("The server did not answer the reflection request in time");
        if (err.get() != null) {
            Status st = Status.fromThrowable(err.get());
            if (st.getCode() == Status.Code.UNIMPLEMENTED) throw new IllegalArgumentException("This server does not support gRPC reflection, so its methods cannot be discovered automatically.");
            throw new IllegalStateException("Reflection failed: " + st.getCode() + " " + (st.getDescription() == null ? "" : st.getDescription()));
        }
        return got;
    }

    private static Descriptors.ServiceDescriptor serviceDescriptor(ManagedChannel ch, String service) throws Exception {
        List<ServerReflectionResponse> rs = reflect(ch, ServerReflectionRequest.newBuilder().setFileContainingSymbol(service).build());
        Map<String, FileDescriptorProto> protos = new HashMap<>();
        for (ServerReflectionResponse r : rs) {
            if (!r.hasFileDescriptorResponse()) continue;
            for (ByteString b : r.getFileDescriptorResponse().getFileDescriptorProtoList()) {
                FileDescriptorProto p = FileDescriptorProto.parseFrom(b);
                protos.put(p.getName(), p);
            }
        }
        for (FileDescriptorProto p : new ArrayList<>(protos.values())) {
            if (p.getServiceList().stream().anyMatch(s -> (p.getPackage().isEmpty() ? s.getName() : p.getPackage() + "." + s.getName()).equals(service))) {
                FileDescriptor fd = build(ch, p, protos, new HashMap<>());
                return fd.getServices().stream().filter(s -> s.getFullName().equals(service)).findFirst().orElseThrow();
            }
        }
        throw new IllegalStateException("The server did not describe service " + service);
    }

    private static FileDescriptor build(ManagedChannel ch, FileDescriptorProto p, Map<String, FileDescriptorProto> known, Map<String, FileDescriptor> built) throws Exception {
        if (built.containsKey(p.getName())) return built.get(p.getName());
        List<FileDescriptor> deps = new ArrayList<>();
        for (String d : p.getDependencyList()) {
            FileDescriptorProto dp = known.get(d);
            if (dp == null) {
                for (ServerReflectionResponse r : reflect(ch, ServerReflectionRequest.newBuilder().setFileByFilename(d).build())) {
                    if (!r.hasFileDescriptorResponse()) continue;
                    for (ByteString b : r.getFileDescriptorResponse().getFileDescriptorProtoList()) {
                        FileDescriptorProto x = FileDescriptorProto.parseFrom(b);
                        known.put(x.getName(), x);
                    }
                }
                dp = known.get(d);
            }
            if (dp == null) throw new IllegalStateException("Missing dependency " + d + " in the reflection answer");
            deps.add(build(ch, dp, known, built));
        }
        FileDescriptor fd = FileDescriptor.buildFrom(p, deps.toArray(new FileDescriptor[0]));
        built.put(p.getName(), fd);
        return fd;
    }

    /** A message with a small valid sample in every field. */
    static DynamicMessage sample(Descriptor d, int depth, java.util.Set<String> guard) {
        DynamicMessage.Builder b = DynamicMessage.newBuilder(d);
        if (depth > 3 || !guard.add(d.getFullName())) return b.build();
        for (FieldDescriptor f : d.getFields()) {
            if (f.isMapField()) continue;
            Object v;
            switch (f.getJavaType()) {
                case STRING: v = "string"; break;
                case INT: v = 1; break;
                case LONG: v = 1L; break;
                case FLOAT: v = 1.5f; break;
                case DOUBLE: v = 1.5d; break;
                case BOOLEAN: v = true; break;
                case BYTE_STRING: v = ByteString.copyFromUtf8("x"); break;
                case ENUM: v = f.getEnumType().getValues().size() > 1 ? f.getEnumType().getValues().get(1) : f.getEnumType().getValues().get(0); break;
                case MESSAGE: v = sample(f.getMessageType(), depth + 1, guard); break;
                default: continue;
            }
            if (f.isRepeated()) b.addRepeatedField(f, v);
            else b.setField(f, v);
        }
        guard.remove(d.getFullName());
        return b.build();
    }

    // ------------------------------------------------------------------ call

    public static Result call(String host, int port, boolean tls, String fullMethod, String json, Map<String, String> headers, long timeoutMs) {
        Result r = new Result();
        long t0 = System.nanoTime();
        ManagedChannel ch = null;
        try {
            ch = channel(host, port, tls);
            String[] parts = fullMethod.replaceFirst("^/", "").split("/");
            if (parts.length != 2) throw new IllegalArgumentException("A gRPC method looks like /package.Service/Method");
            String key = host + ":" + port + "|" + parts[0];
            MethodDescriptor md = null;
            FileDescriptor cached = FILES.get(key);
            Descriptors.ServiceDescriptor sd = cached != null ? cached.getServices().stream().filter(s -> s.getFullName().equals(parts[0])).findFirst().orElse(null) : null;
            if (sd == null) {
                sd = serviceDescriptor(ch, parts[0]);
                FILES.put(key, sd.getFile());
            }
            md = sd.findMethodByName(parts[1]);
            if (md == null) throw new IllegalArgumentException("The service has no method " + parts[1]);
            final Descriptor in = md.getInputType(), out = md.getOutputType();

            DynamicMessage.Builder rb = DynamicMessage.newBuilder(in);
            try {
                JsonFormat.parser().merge(json == null || json.isBlank() ? "{}" : json, rb);
            } catch (Exception e) {
                r.unsendable = true;
                r.error = "The request cannot be encoded as protobuf: " + e.getMessage();
                r.ms = (System.nanoTime() - t0) / 1_000_000;
                return r;
            }
            io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> grpcMd = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                    .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(io.grpc.MethodDescriptor.generateFullMethodName(parts[0], parts[1]))
                    .setRequestMarshaller(marshaller(in)).setResponseMarshaller(marshaller(out)).build();
            Metadata md2 = new Metadata();
            if (headers != null) {
                headers.forEach((k, v) -> {
                    String lk = k.toLowerCase();
                    if (!lk.equals("content-type") && !lk.startsWith(":") && !lk.equals("host")) md2.put(Metadata.Key.of(lk, Metadata.ASCII_STRING_MARSHALLER), v);
                });
            }
            io.grpc.Channel withHeaders = ClientInterceptors.intercept(ch, MetadataUtils.newAttachHeadersInterceptor(md2));
            try {
                DynamicMessage resp = ClientCalls.blockingUnaryCall(withHeaders, grpcMd, CallOptions.DEFAULT.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS), rb.build());
                r.status = 200;
                r.grpcCode = 0;
                r.grpcName = "OK";
                r.body = JsonFormat.printer().includingDefaultValueFields().print(resp);
            } catch (StatusRuntimeException e) {
                Status st = e.getStatus();
                r.grpcCode = st.getCode().value();
                r.grpcName = st.getCode().name();
                r.message = st.getDescription() == null ? "" : st.getDescription();
                r.status = httpStatus(st.getCode());
                r.body = "{\"grpcStatus\":\"" + r.grpcName + "\",\"message\":" + quote(r.message) + "}";
            }
        } catch (IllegalArgumentException e) {
            r.error = e.getMessage();
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (ch != null) ch.shutdownNow();
        }
        r.ms = (System.nanoTime() - t0) / 1_000_000;
        return r;
    }

    private static io.grpc.MethodDescriptor.Marshaller<DynamicMessage> marshaller(Descriptor d) {
        return new io.grpc.MethodDescriptor.Marshaller<DynamicMessage>() {
            public InputStream stream(DynamicMessage v) {
                return v.toByteString().newInput();
            }

            public DynamicMessage parse(InputStream in) {
                try {
                    return DynamicMessage.parseFrom(d, in);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    static int httpStatus(Status.Code c) {
        switch (c) {
            case OK: return 200;
            case INVALID_ARGUMENT: case FAILED_PRECONDITION: case OUT_OF_RANGE: return 400;
            case UNAUTHENTICATED: return 401;
            case PERMISSION_DENIED: return 403;
            case NOT_FOUND: return 404;
            case ALREADY_EXISTS: case ABORTED: return 409;
            case RESOURCE_EXHAUSTED: return 429;
            case UNIMPLEMENTED: return 501;
            case UNAVAILABLE: return 503;
            case DEADLINE_EXCEEDED: return 504;
            default: return 500;
        }
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }
}
