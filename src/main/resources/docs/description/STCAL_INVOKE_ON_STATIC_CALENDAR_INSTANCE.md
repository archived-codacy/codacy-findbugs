Even though the JavaDoc does not contain a hint about it, Calendars are inherently unsafe for multihtreaded use. The detector has found a call to an instance of Calendar that has been obtained via a static field. This looks suspicous.For more information on this see Sun Bug #6231579 and Sun Bug #6178997.