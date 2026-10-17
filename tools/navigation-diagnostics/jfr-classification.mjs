export function classifyJfrSample(sample) {
  const stack=sample.stack??'',clazz=sample.class??'';
  if(/org\.spongepowered\.asm\.mixin\.transformer\.|an\.trace\.Agent\$Transformer|cpw\.mods\.modlauncher\.ClassTransformer/.test(stack))
    return 'classLoadingAndTransform';
  if(/an\.trace\.Trace\.(enter|exit|field|number|point|clock)|an\.trace\.Trace\$/.test(stack))return 'traceObserver';
  if(stack.includes('org.apache.logging.log4j.message.ParameterFormatter'))return 'logFormatting';
  if(stack.includes('NativeNavigationSearch.')||stack.includes('PathFinder.m_77427_'))return 'detailedSearch';
  if(stack.includes('GroundSegmentWindow.'))return 'window';
  if(stack.includes('TopologyService.scanSection'))return 'factScan';
  if(clazz.startsWith('com.scarasol.')||/AutomaticNavigationBridge|NavigationPolicy|SegmentedNavigationService\.(physical|executionReady|planningCurrent)/.test(stack))
    return 'navigationFramework';
  if(clazz.startsWith('an.production.'))return 'commonProbe';
  if(/net.minecraft.world.entity.Entity\.|net.minecraft.world.entity.LivingEntity\.|net.minecraft.world.entity.Mob\./.test(stack))
    return 'entityExecution';
  return 'other';
}
