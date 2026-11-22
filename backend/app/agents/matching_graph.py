"""Compiled LangGraph StateGraph for Hybrid Job Matching."""

from __future__ import annotations

from typing import Any

from langgraph.graph import END, START, StateGraph

from app.agents.nodes import (
    evaluate_and_score_node,
    format_and_persist_node,
    research_companies_node,
    retrieve_candidates_node,
    should_research,
)
from app.agents.state import MatchingAgentState


def build_matching_graph() -> Any:
    """Assemble and compile the Hybrid Matching Agent StateGraph."""
    builder = StateGraph(MatchingAgentState)
    builder.add_node("retrieve", retrieve_candidates_node)
    builder.add_node("research", research_companies_node)
    builder.add_node("evaluate", evaluate_and_score_node)
    builder.add_node("persist", format_and_persist_node)

    builder.add_edge(START, "retrieve")
    builder.add_conditional_edges(
        "retrieve",
        should_research,
        {
            "research": "research",
            "evaluate": "evaluate",
            "end": END,
        },
    )
    builder.add_edge("research", "evaluate")
    builder.add_edge("evaluate", "persist")
    builder.add_edge("persist", END)
    return builder.compile()


matching_graph = build_matching_graph()
