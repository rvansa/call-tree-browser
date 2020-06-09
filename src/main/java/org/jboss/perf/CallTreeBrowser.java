package org.jboss.perf;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Stack;
import java.util.TreeMap;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
@Path("/")
@Produces(MediaType.TEXT_HTML)
public class CallTreeBrowser {
    @ConfigProperty(name = "ctb.file")
    String callTreeFile;

    Map<String, ClassInfo> classes = new TreeMap<>();
    List<MethodInfo> entrypoints = new ArrayList<>();

    static class ClassInfo {
        final String name;
        Map<String, MethodInfo> methods = new TreeMap<>();

        ClassInfo(String name) {
            this.name = name;
        }
    }

    static class MethodInfo {
        final ClassInfo klass;
        final String signature;
        Set<CallInfo> forward = new HashSet<>();
        Set<CallInfo> reverse = new HashSet<>();

        MethodInfo(ClassInfo klass, String signature) {
            this.klass = klass;
            this.signature = signature;
        }
    }

    static class CallInfo {
        final String type;
        final MethodInfo caller;
        final MethodInfo callee;

        CallInfo(String type, MethodInfo caller, MethodInfo callee) {
            this.type = type;
            this.caller = caller;
            this.callee = callee;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CallInfo callInfo = (CallInfo) o;
            return type.equals(callInfo.type) &&
                  caller == callInfo.caller &&
                  callee == callInfo.callee;
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, caller, callee);
        }
    }

    @PostConstruct
    void init() {
        try (BufferedReader reader = new BufferedReader(new FileReader(callTreeFile))) {
            String line;
            reader.readLine(); // skip first row

            Stack<MethodInfo> stack = new Stack<>();

            while ((line = reader.readLine()) != null) {
                int indentIndex = 0;
                for (int i = 0; i < line.length(); ++i) {
                    if (line.charAt(i) != ' ' && line.charAt(i) < 0x80) {
                        indentIndex = i;
                        break;
                    }
                }
                int typeEnd = line.lastIndexOf(' ', line.indexOf('.', indentIndex));
                if (typeEnd < 0) {
                    System.err.println(line);
                    continue;
                }
                String callType = line.substring(indentIndex, typeEnd).intern();
                int methodParamsStart = line.indexOf('(', typeEnd);
                int methodStart = line.lastIndexOf('.', methodParamsStart - 1);
                int methodEnd = line.indexOf(':', methodParamsStart);
                if (methodStart < 0) {
                    System.err.println(line);
                    continue;
                }
                String klass = line.substring(typeEnd + 1, methodStart);
                String method = line.substring(methodStart + 1, methodEnd);

                ClassInfo classInfo = classes.computeIfAbsent(klass, ClassInfo::new);
                MethodInfo callee = classInfo.methods.computeIfAbsent(method, m -> new MethodInfo(classInfo, m));

                final int indent = (indentIndex / 4) - 1;

                while (stack.size() != indent) {
                    stack.pop();
                }
                stack.push(callee);
                if (indent == 0) {
                    assert callType.equals("entry");
                    entrypoints.add(callee);
                    continue;
                }
                MethodInfo caller = stack.get(indent - 1);
                final CallInfo callInfo = new CallInfo(callType, caller, callee);
                caller.forward.add(callInfo);
                callee.reverse.add(callInfo);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private interface WriterConsumer {
        void accept(BufferedWriter writer) throws IOException;
    }

    private Response stream(WriterConsumer consumer) {
        return Response.ok((StreamingOutput) output -> {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output));
            writer.write("<html><head><style>");
            writer.write("a { text-decoration: none; }");
            writer.write("a:hover { text-decoration: underline }");
            writer.write("</style></head><body>");
            consumer.accept(writer);
            writer.write("</body></html>");
            writer.flush();
        }).build();
    }

    private void writeHeader(BufferedWriter writer) throws IOException {
        writer.write("<a href=\"/\">VM entry-points</a>&nbsp;&nbsp;<a href=\"/classes\">class index</a>\n");
    }

    @GET
    @Path("/classes")
    public Response classes() {
        return stream(writer -> {
            writeHeader(writer);
            writer.write("<h1>Classes</h1>\n");
            for (ClassInfo classInfo : classes.values()) {
                writer.write("<a href=\"/class/" + classInfo.name + "\">" + classInfo.name + "</a><br>\n");
            }
        });
    }

    @GET
    @Path("/")
    public Response entrypoints() {
        return stream(writer -> {
            writeHeader(writer);
            writer.write("<h1>VM entry-points</h1>\n");
            for (MethodInfo methodInfo : entrypoints) {
                writer.write("<a href=\"/method/" + methodInfo.klass.name + "/" + encode(methodInfo.signature) + "\">");
                writer.write(methodInfo.klass.name + "." + sanitize(methodInfo.signature) + "</a><br>\n");
            }
        });
    }

    @GET
    @Path("class/{cls}")
    public Response clazz(@PathParam("cls") String klass) {
        ClassInfo classInfo = classes.get(klass);
        if (classInfo == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Class " + klass + " not found").build();
        }
        return stream(writer -> {
            writeHeader(writer);
            writer.write("<h1>class " + classInfo.name + "</h1>\n");
            for (MethodInfo method : classInfo.methods.values()) {
                writer.write("<span style=\"float: left; width: 80px\">IN " + method.reverse.size() + "</span>\n");
                writer.write("<span style=\"float: left; width: 80px\">OUT " + method.forward.size() + "</span>\n");
                String encodedSignature = encode(method.signature);
                writer.write("<a href=\"/method/" + classInfo.name + "/" + encodedSignature + "\">" + sanitize(method.signature) + "</a><br>\n");
            }
        });
    }

    private String encode(String signature) {
        try {
            return URLEncoder.encode(signature, StandardCharsets.UTF_8.name()).replaceAll("\\+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private String sanitize(String signature) {
        return signature.replaceAll("<", "&lt;").replaceAll(">", "&gt;");
    }

    @GET
    @Path("method/{cls}/{m}")
    public Response method(@PathParam("cls") String klass, @PathParam("m") String method) {
        ClassInfo classInfo = classes.get(klass);
        if (classInfo == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Class " + klass + " not found").build();
        }
        MethodInfo methodInfo = classInfo.methods.get(method);
        if (methodInfo == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Method " + method + " not found").build();
        }
        return stream(writer -> {
            writeHeader(writer);
            writer.write("<h1>method " + sanitize(method) + "</h1>\n");
            writer.write("Class: <a href=\"/class/" + classInfo.name + "\">" + classInfo.name + "</a>");
            writer.write("<h2>Calling:</h2>");
            for (CallInfo call : methodInfo.forward) {
                writer.write(call.type + " <a href=\"/method/" + call.callee.klass.name + "/" + encode(call.callee.signature) + "\">");
                writer.write(call.callee.klass.name + "." + sanitize(call.callee.signature) + "</a><br>\n");
            }
            writer.write("<h2>Called by</h2>");
            for (CallInfo call : methodInfo.reverse) {
                writer.write(reverse(call.type) + " <a href=\"/method/" + call.caller.klass.name + "/" + encode(call.caller.signature) + "\">");
                writer.write(call.caller.klass.name + "." + sanitize(call.caller.signature) + "</a><br>\n");
            }
        });
    }

    private String reverse(String type) {
        switch (type) {
            case "directly calls": return "directly called by";
            case "virtually calls": return "virtually called by";
            case "interfacially calls": return "interfacially called by";
            case "is overridden by": return "overrides";
            case "is implemented by": return "implements";
            default:
                return "REV(" + type + ")";
        }

    }
}