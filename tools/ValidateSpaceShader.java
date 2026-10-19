import static org.lwjgl.util.shaderc.Shaderc.*;
import java.nio.file.*;
public class ValidateSpaceShader {
 public static void main(String[] args) throws Exception {
  long compiler=shaderc_compiler_initialize();
  long options=shaderc_compile_options_initialize();
  shaderc_compile_options_set_target_env(options,0,4202496);
  shaderc_compile_options_set_auto_bind_uniforms(options,true);
  shaderc_compile_options_set_preserve_bindings(options,false);
  shaderc_compile_options_set_generate_debug_info(options);
  shaderc_compile_options_set_optimization_level(options,0);
  for (String file:args) {
   String source=Files.readString(Path.of(file));
   int kind=file.endsWith(".fsh")?shaderc_fragment_shader:shaderc_vertex_shader;
   long result=shaderc_compile_into_spv(compiler,source,kind,file,"main",options);
   int status=shaderc_result_get_compilation_status(result);
   System.out.println(file+": status="+status+", warnings="+shaderc_result_get_num_warnings(result));
   System.out.print(shaderc_result_get_error_message(result));
   if(status!=0) System.exit(1);
   System.out.println("SPIR-V bytes="+shaderc_result_get_bytes(result).remaining());
   shaderc_result_release(result);
  }
  shaderc_compile_options_release(options);
  shaderc_compiler_release(compiler);
 }
}
