# ArrayList 与 LinkedList：先定位，再修改

ArrayList 用可扩容数组保存元素。按下标 get/set 为 O(1)；末尾追加的均摊复杂度为 O(1)，发生扩容时需要复制元素。在中间插入或删除通常要移动后面的元素，因此为 O(n)。

LinkedList 是双向链表。按下标定位元素需要从较近的一端遍历，最坏为 O(n)。已经通过迭代器定位到节点时，修改相邻链接的插入或删除可以是 O(1)。这不意味着按下标插入的整体成本也是 O(1)：需要把寻找位置的成本算进去。

面试中不要直接说“查多用 ArrayList，增删多用 LinkedList”。应补充访问方式、插入位置、是否已持有迭代器位置，以及实际测量结果。链表每个元素还需要节点对象和链接；大 O 相同也不保证实际耗时相同。

在本项目中，检索候选片段放进 List 后顺序计算相似度、排序；没有频繁的中间插入需求，使用 ArrayList 收集结果很自然。这里是业务推理示例，未做性能基准测试。

参考：Oracle List Implementations（JDK 8 教程，本文仅使用其集合实现与访问成本说明）：
https://docs.oracle.com/javase/tutorial/collections/implementations/list.html
ArrayList API：
https://docs.oracle.com/en/java/javase/18/docs/api/java.base/java/util/ArrayList.html
