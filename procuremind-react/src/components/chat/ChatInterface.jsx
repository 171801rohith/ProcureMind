import React, { useState, useRef, useEffect } from 'react';
import { useApp } from '../../context/AppContext';
import { Card } from '../ui/Card';
import { Button } from '../ui/Button';
import { ApiClient } from '../../api/client';
import { Bot, User, Send, ChevronDown, ChevronUp, Sparkles, Trash2 } from 'lucide-react';

export function ChatInterface() {
  const { conversationId, chatHistory, setChatHistory } = useApp();
  const [userMsg, setUserMsg] = useState('');
  const [isSending, setIsSending] = useState(false);
  const [expandedTraceIdx, setExpandedTraceIdx] = useState(null);
  const chatEndRef = useRef(null);

  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [chatHistory]);

  const handleSend = async (queryText) => {
    const textToSend = queryText || userMsg;
    if (!textToSend.trim() || isSending) return;

    const timeStr = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    const newHistory = [
      ...chatHistory,
      { sender: 'user', text: textToSend, timestamp: timeStr }
    ];
    setChatHistory(newHistory);
    setUserMsg('');
    setIsSending(true);

    try {
      const res = await ApiClient.sendChatMessage(textToSend, conversationId);
      const agentResponse = {
        sender: 'agent',
        text: res?.answer || res?.response || 'No response returned from API Gateway.',
        timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
        agentTrace: res?.agentTrace || []
      };
      setChatHistory([...newHistory, agentResponse]);
    } catch {
      setChatHistory([
        ...newHistory,
        {
          sender: 'agent',
          text: 'Unable to connect to API Gateway chat service.',
          timestamp: timeStr,
          agentTrace: ['Network connection error']
        }
      ]);
    } finally {
      setIsSending(false);
    }
  };

  const handleClear = () => {
    setChatHistory([
      {
        sender: 'agent',
        text: 'Session history reset. Ask me anything about your contract portfolio.',
        timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
        agentTrace: ['Reset chat session']
      }
    ]);
  };

  return (
    <div className="h-[calc(100vh-6.5rem)] flex flex-col space-y-3 sm:space-y-4">
      {/* Header Banner */}
      <div className="bg-slate-900 border border-slate-800 p-3 sm:p-4 rounded-xl flex items-center justify-between gap-2">
        <div className="min-w-0">
          <h3 className="text-sm sm:text-base font-bold text-slate-100 flex items-center gap-2 truncate">
            <Bot className="w-4 h-4 sm:w-5 sm:h-5 text-blue-400 shrink-0" /> AI Assistant Workspace
          </h3>
          <p className="text-[11px] sm:text-xs text-slate-400 truncate">
            Session: <code className="text-blue-400 font-mono">{conversationId}</code>
          </p>
        </div>
        <Button variant="secondary" size="sm" onClick={handleClear} className="gap-1 px-2.5 text-xs shrink-0">
          <Trash2 className="w-3.5 h-3.5 text-slate-400" />
          <span className="hidden sm:inline">Reset</span>
        </Button>
      </div>

      {/* Suggested Prompts with Horizontal Touch Scroll on Mobile */}
      <div className="flex items-center gap-2 overflow-x-auto pb-1 no-scrollbar text-xs">
        <button
          onClick={() => handleSend('What is our highest risk contract?')}
          className="shrink-0 bg-slate-900 hover:bg-slate-800 border border-slate-800 text-slate-300 px-3 py-1.5 rounded-full transition-colors flex items-center gap-1.5 text-xs"
        >
          <Sparkles className="w-3 h-3 text-red-400" /> Highest Risk
        </button>
        <button
          onClick={() => handleSend('Show me Acme Corp MSA risks')}
          className="shrink-0 bg-slate-900 hover:bg-slate-800 border border-slate-800 text-slate-300 px-3 py-1.5 rounded-full transition-colors flex items-center gap-1.5 text-xs"
        >
          <Sparkles className="w-3 h-3 text-amber-400" /> Acme Corp
        </button>
        <button
          onClick={() => handleSend('Summarize indemnification clauses')}
          className="shrink-0 bg-slate-900 hover:bg-slate-800 border border-slate-800 text-slate-300 px-3 py-1.5 rounded-full transition-colors flex items-center gap-1.5 text-xs"
        >
          <Sparkles className="w-3 h-3 text-blue-400" /> Indemnification
        </button>
      </div>

      {/* Message Thread */}
      <Card className="flex-1 overflow-y-auto p-3 sm:p-4 space-y-4">
        {chatHistory.map((msg, idx) => {
          const isUser = msg.sender === 'user';
          return (
            <div key={idx} className={`flex flex-col ${isUser ? 'items-end' : 'items-start'}`}>
              <div className="flex items-center space-x-1.5 mb-1 text-[10px] sm:text-[11px] text-slate-400">
                {isUser ? <User className="w-3 h-3 text-slate-400" /> : <Bot className="w-3 h-3 text-blue-400" />}
                <span className="font-semibold">{isUser ? 'You' : 'ProcureMind AI'}</span>
                <span>• {msg.timestamp}</span>
              </div>

              <div
                className={`max-w-[90%] sm:max-w-xl lg:max-w-2xl px-3.5 py-2.5 sm:px-4 sm:py-3 rounded-xl text-xs sm:text-sm leading-relaxed ${
                  isUser
                    ? 'bg-slate-800 text-slate-100 border border-slate-700 rounded-tr-none'
                    : 'bg-slate-950 text-slate-200 border border-blue-900/50 rounded-tl-none shadow-lg'
                }`}
              >
                <div className="whitespace-pre-wrap">{msg.text}</div>

                {/* Agent Trace Accordion */}
                {!isUser && msg.agentTrace && msg.agentTrace.length > 0 && (
                  <div className="mt-2.5 pt-2 border-t border-slate-900">
                    <button
                      onClick={() => setExpandedTraceIdx(expandedTraceIdx === idx ? null : idx)}
                      className="text-[11px] text-blue-400 hover:text-blue-300 flex items-center gap-1 font-medium"
                    >
                      <span>Execution Trace</span>
                      {expandedTraceIdx === idx ? <ChevronUp className="w-3 h-3" /> : <ChevronDown className="w-3 h-3" />}
                    </button>
                    {expandedTraceIdx === idx && (
                      <div className="mt-2 bg-slate-950 border-l-2 border-blue-500 p-2 rounded font-mono text-[10px] text-blue-300 space-y-1">
                        {msg.agentTrace.map((step, sIdx) => (
                          <div key={sIdx}>• ⚙️ {step}</div>
                        ))}
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>
          );
        })}
        <div ref={chatEndRef} />
      </Card>

      {/* Input Form */}
      <form
        onSubmit={(e) => {
          e.preventDefault();
          handleSend();
        }}
        className="flex gap-2"
      >
        <input
          type="text"
          value={userMsg}
          onChange={(e) => setUserMsg(e.target.value)}
          placeholder="Ask ProcureMind AI a question..."
          className="flex-1 bg-slate-900 border border-slate-800 rounded-xl px-3.5 py-2.5 sm:px-4 sm:py-3 text-xs sm:text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:border-blue-500"
          disabled={isSending}
        />
        <Button type="submit" variant="primary" isLoading={isSending} disabled={!userMsg.trim()} className="gap-1.5 px-4 sm:px-5">
          <span className="hidden sm:inline">Send</span>
          <Send className="w-4 h-4" />
        </Button>
      </form>
    </div>
  );
}
