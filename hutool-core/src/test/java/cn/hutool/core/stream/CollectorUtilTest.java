package cn.hutool.core.stream;

import cn.hutool.core.map.MapUtil;
import org.junit.Assert;
import org.junit.Test;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * CollectorUtilTest
 *
 * @author VampireAchao
 * @since 2022/7/3
 */
public class CollectorUtilTest {

	@Test
	public void reduceListMapTest() {
		final Set<Map<String, Integer>> nameScoreMapList = StreamUtil.of(
				// 集合内的第一个map，包含两个key value
				MapUtil.builder("苏格拉底", 1).put("特拉叙马霍斯", 3).build(),
				MapUtil.of("苏格拉底", 2),
				MapUtil.of("特拉叙马霍斯", 1),
				MapUtil.of("特拉叙马霍斯", 2)
		).collect(Collectors.toSet());
		// 执行聚合
		final Map<String, List<Integer>> nameScoresMap = nameScoreMapList.stream().collect(CollectorUtil.reduceListMap());

		Assert.assertEquals(MapUtil.builder("苏格拉底", Arrays.asList(1, 2))
						.put("特拉叙马霍斯", Arrays.asList(3, 1, 2)).build(),
				nameScoresMap);
	}

	@Test
	public void testTransform() {
		Stream<Integer> stream = Stream.of(1, 2, 3, 4)
				.collect(CollectorUtil.transform(EasyStream::of));
		Assert.assertEquals(EasyStream.class, stream.getClass());

		stream = Stream.of(1, 2, 3, 4)
				.collect(CollectorUtil.transform(HashSet::new, EasyStream::of));
		Assert.assertEquals(EasyStream.class, stream.getClass());
	}

	@Test
	public void testToEasyStream() {
		final Stream<Integer> stream = Stream.of(1, 2, 3, 4)
				.collect(CollectorUtil.toEasyStream());
		Assert.assertEquals(EasyStream.class, stream.getClass());
	}

	@Test
	public void testToEntryStream() {
		final Map<String, Integer> map = Stream.of(1, 2, 3, 4, 5)
				// 转为EntryStream
				.collect(CollectorUtil.toEntryStream(Function.identity(), String::valueOf))
				// 过滤偶数
				.filterByKey(k -> (k & 1) == 1)
				.inverse()
				.toMap();
		Assert.assertEquals((Integer) 1, map.get("1"));
		Assert.assertEquals((Integer) 3, map.get("3"));
		Assert.assertEquals((Integer) 5, map.get("5"));
	}

	@Test
	public void testFiltering() {
		final Map<Integer, Long> map = Stream.of(1, 2, 3)
				.collect(Collectors.groupingBy(Function.identity(),
						CollectorUtil.filtering(i -> i > 1, Collectors.counting())
				));
		Assert.assertEquals(MapUtil.builder()
				.put(1, 0L)
				.put(2, 1L)
				.put(3, 1L)
				.build(), map);
	}

	@Test
	public void testGroupingByAfterValueMapped() {
		List<Integer> list = Arrays.asList(1, 1, 2, 2, 3, 4);
		Map<Boolean, Set<String>> map = list.stream()
			.collect(CollectorUtil.groupingBy(t -> (t & 1) == 0, String::valueOf, LinkedHashSet::new, LinkedHashMap::new));

		Assert.assertEquals(LinkedHashMap.class, map.getClass());
		Assert.assertEquals(new LinkedHashSet<>(Arrays.asList("2", "4")), map.get(Boolean.TRUE));
		Assert.assertEquals(new LinkedHashSet<>(Arrays.asList("1", "3")), map.get(Boolean.FALSE));

		map = list.stream()
			.collect(CollectorUtil.groupingBy(t -> (t & 1) == 0, String::valueOf, LinkedHashSet::new));
		Assert.assertEquals(HashMap.class, map.getClass());
		Assert.assertEquals(new LinkedHashSet<>(Arrays.asList("2", "4")), map.get(Boolean.TRUE));
		Assert.assertEquals(new LinkedHashSet<>(Arrays.asList("1", "3")), map.get(Boolean.FALSE));

		Map<Boolean, List<String>> map2 = list.stream()
			.collect(CollectorUtil.groupingBy(t -> (t & 1) == 0, String::valueOf));
		Assert.assertEquals(Arrays.asList("2", "2", "4"), map2.get(Boolean.TRUE));
		Assert.assertEquals(Arrays.asList("1", "1", "3"), map2.get(Boolean.FALSE));

	}

}
